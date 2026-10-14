"""Real Alloy upload, publication and execution witnesses for dependency contexts.

Owned scratch and sequential JVMs only. No provider, live configuration or new
formal closure claim is involved. The external SmartContract fixture is optional
on clones; the portable chain and parameterized fixtures always execute.
"""
from contextlib import nullcontext
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import admin_upload as upload
import exercise_store as store
from practice_dependencies import render_context, validation_record
from runtime_dependencies import clean_java_environment, runtime_classpath, run_engine


ROOT = Path(__file__).resolve().parents[1]
SCRATCH = ROOT / 'build/practice-dependency-integration'
SMART_CONTRACT = Path('/home/augustus/Alloy Smart Contract/SmartContract.als')
PORTABLE_SMART_CONTRACT = '''module SmartContract
sig Address { var ether: one Int }
one sig Vault extends Address {}
pred sendEther[from, to: Address, amt: Int] {
  from.ether >= amt
  ether' = ether ++ (from -> minus[from.ether, amt] + to -> plus[to.ether, amt])
}
pred deposit[s: Address, amt: Int] { amt > 0 and sendEther[s, Vault, amt] }
'''


class PracticeDependencyIntegrationTests(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='integration-', dir=SCRATCH)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        patched = patch.object(upload, 'runtime_classpath', return_value=runtime_classpath(ROOT))
        patched.start()
        self.addCleanup(patched.stop)

    def prepare(self, source, selected, *, model_id='dependencies'):
        calls, engine = [], upload.run_engine

        def observed(arguments, **kwargs):
            started = time.monotonic()
            result = engine(arguments, **kwargs)
            calls.append((arguments[-2] if arguments[-1] == '--batch' else arguments[-1],
                          len(kwargs['input'].splitlines()) if arguments[-1] == '--batch' else 1,
                          time.monotonic() - started))
            return result

        with patch.object(upload, 'run_engine', side_effect=observed):
            prepared = upload.prepare_upload(self.root, dict(source=source, filename='dependencies.als',
                modelId=model_id, practicePredicates=selected))
        self.assertEqual([call[0] for call in calls], ['live.UploadInspector', 'live.ExerciseValidator'])
        self.assertEqual(calls[1][1], len(selected))
        self.assertEqual(prepared['witness']['version'], 3)
        self.assertTrue(upload.validate_witness(prepared['witness'], prepared['documents']))
        self.assertTrue(all(certificate['version'] == 2 for certificate in prepared['certificates']))
        self.assertLess(sum(call[2] for call in calls), 60)
        return prepared

    def invoke(self, main, payload):
        result = run_engine(['java', '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2',
                             '-cp', runtime_classpath(ROOT), main], input=json.dumps(payload),
                            capture_output=True, text=True, encoding='utf-8', timeout=60,
                            env=clean_java_environment(), check=False, root=self.root)
        self.assertEqual(result.returncode, 0, 'An integration JVM failed its exit boundary.')
        self.assertEqual(result.stderr, '', 'An integration JVM exposed raw diagnostics.')
        try:
            return json.loads(result.stdout)
        except (ValueError, UnicodeError):
            self.fail('An integration JVM returned unreadable JSON.')

    def feedback(self, base, bodies, learner, *, metric='canonical'):
        record = render_context(base, bodies)
        result = self.invoke('live.LiveFeedback', dict(studentSource=store.model(record, learner),
            referenceBodies=base['oracleSolutions'],
            referencePrefix=record['environmentBefore'] + record['predicateHeader'] + '{\n',
            referenceSuffix='\n}' + record['environmentAfter'], predicate=base['predicate'], metric=metric,
            blockedPredicates=base['practicePlan']['blockedPredicates']))
        return result

    def behavior(self, base, bodies, learner):
        record = render_context(base, bodies)
        return self.invoke('live.BehaviorFeedback', dict(studentSource=store.model(record, learner),
            studentBody=learner, oracleSource=store.model(record, base['oracleSolutions'][0]),
            predicate=base['predicate'], blockedPredicates=base['practicePlan']['blockedPredicates']))

    def publish(self, prepared):
        from tests.test_sqlite_store import fixture
        fixture(self.root)
        metadata = [dict(predicate=document['predicate'], title=document['title'], question=document['description'])
                    for document in prepared['documents']]
        store.commit_upload(self.root, prepared, metadata, guard=nullcontext)
        loaded = store.load_store(self.root)
        for document in prepared['documents']:
            actual = loaded.exercises[document['id']]
            self.assertEqual(actual['practicePlan'], document['practicePlan'])
            self.assertEqual(actual['sourceInspectionSha256'], document['sourceInspectionSha256'])
        return loaded

    def test_helper_chain_publishes_and_both_metrics_and_behavior_use_same_learner_context(self):
        source = ('sig Node {}\npred A {some Node}\npred retainedHelper {A[]}\n'
                  'pred B {retainedHelper[]}\npred C {B[]}\n')
        prepared = self.prepare(source, ['A', 'B', 'C'])
        a, b, c = prepared['documents']
        self.assertEqual(c['practicePlan']['dependencies'], ['dependencies-A', 'dependencies-B'])
        self.assertNotIn('some Node', c['environmentBefore'])
        self.assertIn('some Node', validation_record(c)['environmentBefore'])
        self.assertIn('pred retainedHelper {A[]}', c['environmentBefore'])
        loaded = self.publish(prepared)
        context = {a['id']: 'some Node', b['id']: 'retainedHelper[]'}
        for metric in ('canonical', 'ast'):
            result = self.feedback(c, context, 'B[]', metric=metric)
            self.assertEqual(result.get('status'), 'ok', 'A verified chain did not compile for this metric.')
            self.assertEqual(result.get('distance'), 0)
        behavior = self.behavior(c, context, 'B[]')
        self.assertEqual((behavior.get('status'), behavior.get('score')), ('ok', 1))
        self.assertTrue(all(category['status'] == 'unsat' and category['enumerationComplete']
                            for category in behavior['categories'] if category['id'] in ('undercoverage', 'overcoverage')))
        record = loaded.exercises[b['id']]
        candidate = 'retainedHelper[] and retainedHelper[]'
        approval = store._approval_document(record, candidate, 5)
        payload = store._validation_payload(approval)
        self.assertIn('some Node', payload['sourcePrefix'])
        certificate = store._validation_certificate(approval, self.invoke('live.ExerciseValidator', payload),
                                                     store._engine_identity(runtime_classpath(ROOT)))
        store.validate_approval(record, candidate, certificate)

    def test_parameterized_numbered_aliases_forward_exact_arguments(self):
        source = ('sig Node {edge: set Node}\npred Inv1C0[n: Node] {no n.edge}\n'
                  'pred Inv1C1[n: Node] {not some n.edge}\npred Inv2C0[n: Node] {Inv1C1[n]}\n')
        prepared = self.prepare(source, ['Inv1', 'Inv2'])
        first, second = prepared['documents']
        self.assertEqual(second['predicateHeader'], 'pred Inv2[n: Node] ')
        self.assertEqual(second['practicePlan']['dependencies'], ['dependencies-Inv1'])
        self.assertIn('pred Inv1C1[n: Node] {\nInv1[n]\n}', second['environmentBefore'])
        context = {first['id']: 'no n.edge'}
        for metric in ('canonical', 'ast'):
            result = self.feedback(second, context, 'Inv1C1[n]', metric=metric)
            self.assertEqual((result.get('status'), result.get('distance')), ('ok', 0))
        behavior = self.behavior(second, context, 'Inv1C1[n]')
        self.assertEqual((behavior.get('status'), behavior.get('score')), ('ok', 1))
        for category in behavior['categories']:
            for instance in category['instances']:
                self.assertEqual([value['name'] for value in instance['parameterBindings']], ['n'])
        self.publish(prepared)

    def test_temporal_dependency_lfu_sequence_and_python_full_reward_gate(self):
        from candidate_store import behavioral_evidence
        from server import project_behavior

        # Exactly ten states, with loop 9: shorter traces cannot satisfy the
        # facts. Left/Right share the same concrete trace but differ as n.
        tail = 'always Active=Other'
        for _ in range(9):
            tail = 'Active=Left+Right and after (' + tail + ')'
        source = ('abstract sig N {}\none sig Left, Right, Other extends N {}\n'
                  'var sig Active in N {}\nfact {' + tail + '}\n'
                  'pred prior[n:N] {n in Active}\npred target[n:N] {prior[n]}\n')
        prepared = self.prepare(source, ['prior', 'target'], model_id='rwd-sequence')
        prior, target = prepared['documents']
        self.assertEqual(target['practicePlan']['dependencies'], [prior['id']])

        def payload(body, prerequisite='n in Active', *, reset=False, blocked=None):
            resolved = render_context(target, {prior['id']: prerequisite})
            return dict(studentSource=store.model(resolved, body), studentBody=body,
                oracleSource=store.model(resolved, target['oracleSolutions'][0]), predicate='target',
                blockedPredicates=(target['practicePlan']['blockedPredicates']
                                   if blocked is None else blocked), resetPools=reset)

        requests = [payload('prior[n]'), payload('n=Left'), payload('n=Left'),
                    payload('eventually n in Active'), payload('prior[n]'),
                    payload('prior[n]', 'n not in Active'),
                    payload('prior[n]', 'n not in Active', reset=True),
                    # Learner does not call prior; the retained oracle does.
                    payload('n=Left', 'n not in Active', blocked=['prior']),
                    payload('prior[n]')]
        classes = self.root / 'sequence-classes'
        classes.mkdir()
        classpath = runtime_classpath(ROOT)
        compiled = subprocess.run(['javac', '-J-Xmx256m', '-J-XX:ActiveProcessorCount=2',
            '-encoding', 'UTF-8', '--release', '17', '-cp', classpath, '-d', str(classes),
            str(ROOT / 'engine/test/BehaviorSequence.java')], cwd=ROOT,
            capture_output=True, timeout=30, env=clean_java_environment())
        self.assertEqual(compiled.returncode, 0, 'Behavior sequence driver did not compile')
        run = run_engine(['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
            str(classes) + os.pathsep + classpath, 'live.BehaviorSequence'],
            input=json.dumps(requests), text=True, encoding='utf-8', capture_output=True,
            timeout=60, env=clean_java_environment(), check=False, root=self.root)
        self.assertEqual((run.returncode, run.stderr), (0, ''), 'Behavior sequence failed')
        observations = json.loads(run.stdout)
        self.assertEqual(len(observations), len(requests))
        checked = {}
        for index, observation in enumerate(observations):
            if index == 7:
                continue
            checked[index] = project_behavior(observation['result'])
            for category in checked[index]['categories']:
                for instance in category['instances']:
                    self.assertEqual((instance['traceLength'], instance['loopState']), (10, 9))
                    states = [{s['label']: set(s['atoms']) for s in state['signatures']}
                              for state in instance['states']]
                    self.assertEqual([s['Active'] for s in states],
                                     [{'Left$0', 'Right$0'}] * 9 + [{'Other$0'}])
                    argument = instance['parameterBindings'][0]['tuples'][0][0]
                    oracle = argument in states[0]['Active']
                    if index in (5, 6):
                        oracle = not oracle
                    student = (argument == 'Left$0' if index in (1, 2) else
                               any(argument in s['Active'] for s in states) if index == 3 else oracle)
                    self.assertEqual((category['oracle'], category['student']), (oracle, student))

        # Both bindings must survive together; categories alone cannot test LFU identity.
        self.assertEqual(checked[1]['sampling'], dict(positiveTested=2, positiveAccepted=1,
            negativeTested=1, negativeRejected=1, semanticCounterexamples=0))
        self.assertEqual(checked[1]['score'], 0.5)
        left = checked[1]['categories'][0]['instances'][0]
        right = checked[1]['categories'][1]['instances'][0]
        self.assertEqual(left['states'], right['states'])
        self.assertNotEqual(left['parameterBindings'], right['parameterBindings'])
        # Exhausted cursors + duplicate witnesses: only a mismatch can bump the epoch.
        self.assertEqual(checked[1], checked[2])
        before, after = (observations[i]['poolState'] for i in (1, 2))
        self.assertEqual(after, [before[0], before[1], before[2] + 1, before[3]])
        self.assertEqual(checked[3]['score'], 0)
        self.assertEqual(checked[3]['categories'][2]['status'], 'sat')
        # Warm context replacement must match a cold evaluation, including LFU state.
        self.assertEqual(observations[5], observations[6])
        denied = observations[7]['result']
        self.assertEqual((denied['status'], denied['diagnostics'][0]['code']),
                         ('unsupported', 'UNSOLVED_PRACTICE_CALL'))
        self.assertNotIn('score', denied)
        self.assertEqual(observations[7]['poolState'], [0, 0, 0, 0])
        self.assertEqual(observations[8], observations[0])
        for index in (0, 4, 5, 6, 8):
            self.assertEqual(checked[index]['score'], 1)
            # This is also the behavioral admission predicate used by completion minting.
            behavioral_evidence(checked[index])
        for index in (1, 2, 3):
            with self.assertRaises(store.StoreError):
                behavioral_evidence(checked[index])
            forged = deepcopy(observations[index]['result'])
            forged['score'] = 1.0
            with self.assertRaises(ValueError):
                project_behavior(forged)

    def test_unsolved_sibling_and_helper_calls_are_rejected_on_all_live_paths(self):
        source = ('sig Node {}\npred A {some Node}\npred B {A[]}\npred C {no Node}\n'
                  'pred retainedHelper {C[]}\n')
        prepared = self.prepare(source, ['A', 'B', 'C'])
        a, b, _ = prepared['documents']
        context = {a['id']: 'some Node'}
        for learner in ('C[]', 'retainedHelper[]'):
            for metric in ('canonical', 'ast'):
                result = self.feedback(b, context, learner, metric=metric)
                self.assertNotEqual(result.get('status'), 'ok')
                self.assertNotIn('distance', result)
            behavior = self.behavior(b, context, learner)
            self.assertNotEqual(behavior.get('status'), 'ok')
            self.assertNotIn('score', behavior)

    def test_100_dependent_practices_keep_two_sequential_jvms_and_topological_metadata(self):
        source = 'sig Node {}\npred practice0 {some Node}\n' + ''.join(
            f'pred practice{index} {{practice{index - 1}[]}}\n' for index in range(1, 100))
        prepared = self.prepare(source, [f'practice{index}' for index in range(100)])
        last = prepared['documents'][-1]
        self.assertEqual(len(last['practicePlan']['dependencies']), 99)
        self.assertEqual(last['practicePlan']['direct'], ['dependencies-practice98'])
        self.assertLessEqual(upload.prepared_size(prepared), upload.MAX_PREPARED_BYTES)
        self.publish(prepared)

    def test_real_http_completion_chain_progress_and_edits_reuse_persistent_workers(self):
        import engine_workers
        import server
        source = 'sig Node {}\npred A {some Node}\npred B {A[]}\npred C {B[]}\n'
        prepared = self.prepare(source, ['A', 'B', 'C'])
        self.publish(prepared)
        with patch.object(engine_workers, 'runtime_classpath', return_value=runtime_classpath(ROOT)):
            app = server.Portal(('127.0.0.1', 0), root=self.root, engine_mode='persistent', timeout=15,
                traffic_profile=server.TrafficProfile(public_burst=10000, peer_burst=10000))
            thread = threading.Thread(target=app.serve_forever, daemon=True)
            thread.start()
            revision = 0
            address = 'http://127.0.0.1:' + str(app.server_port)
            def request(path, data=None):
                wire = Request(address + path, data=None if data is None else json.dumps(data).encode('utf-8'),
                               headers={'Content-Type': 'application/json'})
                try:
                    response = urlopen(wire, timeout=45)
                except HTTPError as error:
                    response = error
                with response:
                    return response.status, json.loads(response.read())
            def payload(name, body, tokens=()):
                nonlocal revision
                revision += 1
                return dict(exerciseId='dependencies-' + name, body=body, revision=revision,
                            channel=channel, metric='canonical', prerequisiteTokens=list(tokens))
            def completed(name, body, tokens=()):
                submitted = payload(name, body, tokens)
                status, feedback = request('/api/feedback', submitted)
                self.assertEqual((status, feedback.get('status'), feedback.get('distance')), (200, 'ok', 0))
                status, behavior = request('/api/behavior', dict(submitted, evidenceToken=feedback['evidenceToken']))
                self.assertEqual((status, behavior.get('status'), behavior.get('score')), (200, 'ok', 1))
                self.assertRegex(behavior.get('completionToken', ''), r'^[A-Za-z0-9_-]{43}$')
                return behavior['completionToken']
            def pids():
                with app.engine_pool.condition:
                    return {lane: tuple(worker.process.pid for worker in workers)
                            for lane, workers in app.engine_pool.workers.items()}
            try:
                channel = request('/api/channel', {})[1]['channel']
                self.assertEqual(app.engine_pool.launches, 0)
                status, progress = request('/api/progress', {'completionTokens': []})
                self.assertEqual(status, 200)
                self.assertEqual(progress['completions'], [])
                request('/api/exercises')
                for path in ('/api/feedback', '/api/behavior', '/api/explain'):
                    status, locked = request(path, payload('B', 'A[]'))
                    self.assertEqual((status, locked.get('status')), (423, 'locked'))
                self.assertEqual(app.engine_pool.launches, 0)
                a = completed('A', 'some Node')
                baseline = pids()
                self.assertTrue(all(len(values) == 1 for values in baseline.values()))
                b = completed('B', 'A[]', [a])
                c = completed('C', 'B[]', [b])
                self.assertEqual(pids(), baseline)
                self.assertEqual(app.engine_pool.launches, 2)
                for token in (a, b, c):
                    status, progress = request('/api/progress', {'completionTokens': [token]})
                    self.assertEqual((status, len(progress['completions'])), (200, 1))
                self.assertEqual(app.engine_pool.launches, 2)
                with patch.object(app, '_engine', wraps=app._engine) as calls:
                    new_a = completed('A', 'some Node /* changed learner draft */')
                    before = app.engine_pool.launches
                    # Anonymous capabilities represent immutable verified code.
                    # A second valid A implementation does not revoke another
                    # learner's still-coherent old B -> A capability chain.
                    status, previous = request('/api/feedback', payload('C', 'B[]', [b]))
                    self.assertEqual((status, previous.get('status'), previous.get('distance')), (200, 'ok', 0))
                    self.assertEqual(app.engine_pool.launches, before)
                    new_b = completed('B', 'A[]', [new_a])
                for call in calls.call_args_list:
                    kind, frame = call.args
                    if frame['predicate'] == 'B':
                        self.assertIn('some Node /* changed learner draft */', frame['studentSource'])
                        other = frame['oracleSource'] if kind == 'behavior' else frame['referencePrefix']
                        self.assertIn('some Node /* changed learner draft */', other)
                self.assertNotEqual(new_b, b)
                self.assertEqual(pids(), baseline)
                self.assertEqual(app.engine_pool.launches, 2)
            finally:
                app.shutdown()
                app.server_close()
                thread.join(timeout=5)
                self.assertFalse(thread.is_alive())

    def test_supplied_smart_contract_selects_parameterized_sendether_as_deposit_prerequisite(self):
        source = SMART_CONTRACT.read_text(encoding='utf-8') if SMART_CONTRACT.is_file() else PORTABLE_SMART_CONTRACT
        prepared = self.prepare(source, ['sendEther', 'deposit'], model_id='smart-contract')
        first, dependent = prepared['documents']
        self.assertEqual(dependent['practicePlan']['direct'], ['smart-contract-sendEther'])
        self.assertEqual(dependent['practicePlan']['dependencies'], ['smart-contract-sendEther'])
        self.assertEqual(first['predicateHeader'], 'pred sendEther[from, to: Address, amt: Int] ')
        self.assertEqual(dependent['predicateHeader'], 'pred deposit[s: Address, amt: Int] ')
        self.assertNotIn(first['oracleSolutions'][0], dependent['environmentBefore'])
        self.assertIn(first['oracleSolutions'][0], validation_record(dependent)['environmentBefore'])
        self.assertEqual(prepared['witness']['originalSource'], source)
        self.publish(prepared)
        observed = {}
        for metric in ('canonical', 'ast'):
            result = self.feedback(dependent, {first['id']: first['oracleSolutions'][0]},
                                   dependent['oracleSolutions'][0], metric=metric)
            observed[metric] = dict(status=result.get('status'), distance=result.get('distance'),
                                    codes=[item.get('code') for item in result.get('diagnostics', [])])
            if metric == 'canonical' and result.get('status') == 'unsupported':
                # Existing ACGN arithmetic normalization is a documented finite
                # boundary, not a successful zero-distance result or unlock.
                self.assertEqual(observed[metric]['codes'], ['UNSUPPORTED_FORM'])
                self.assertNotIn('distance', result)
            else:
                self.assertEqual((result.get('status'), result.get('distance')), ('ok', 0),
                                 str(dict(metric=metric, diagnostics=result.get('diagnostics'))))
        receipt = SCRATCH / ('smart-contract-' + str(time.time_ns()) + '.json')
        receipt.write_text(json.dumps(dict(fixture='supplied-private' if SMART_CONTRACT.is_file() else 'portable-representative',
                                          preparation='PASS', publication='PASS', metrics=observed), indent=2) + '\n')


if __name__ == '__main__':
    unittest.main()
