"""Real Alloy witnesses for bounded batch upload and explicit practice selection."""
from contextlib import contextmanager
from copy import deepcopy
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

import admin_upload as upload
import exercise_store as store
from runtime_dependencies import clean_java_environment, runtime_classpath
from test_sqlite_store import fixture


ROOT = Path(__file__).resolve().parents[1]
SCRATCH = ROOT / 'build/upload-capacity-validation'
BASE = 'sig Node {}\n'


def envelope(source, **values):
    return dict(source=source, filename='capacity.als', modelId='capacity', **values)


def grouped(count, *, spelling=False, equivalent=False):
    source = BASE
    for index in range(1, count + 1):
        source += f'pred inv{index}C0 {{some Node}}\n'
        if spelling or equivalent:
            body = 'some  Node' if spelling else 'not no Node'
            source += f'pred inv{index}C1 {{{body}}}\n'
    return source


def inspect(payload):
    """Own admission independently of production roots closed by shutdown tests."""
    SCRATCH.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='inspector-', dir=SCRATCH) as folder:
        result = upload.run_engine(['java', '-Dfile.encoding=UTF-8', '-Xmx256m',
                                   '-XX:ActiveProcessorCount=2', '-cp', runtime_classpath(ROOT),
                                   'live.UploadInspector'], input=json.dumps(payload), root=Path(folder),
                                   capture_output=True, text=True, encoding='utf-8', timeout=60,
                                   env=clean_java_environment(), check=False)
    if result.returncode or result.stderr:
        raise AssertionError('Inspector violated its sanitized process boundary.')
    return json.loads(result.stdout)


@contextmanager
def permitted():
    yield


class AdminSelectionTests(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='selection-', dir=SCRATCH)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        # Real compiled classes remain in ROOT; runtime admission and scratch
        # belong to this fixture. Never reopen a production shutdown boundary.
        classpath = patch.object(upload, 'runtime_classpath', return_value=runtime_classpath(ROOT))
        classpath.start()
        self.addCleanup(classpath.stop)

    def measured_prepare(self, source, *, expected_frames, **values):
        calls = []
        engine = upload.run_engine

        def observe(arguments, **kwargs):
            started = time.monotonic()
            result = engine(arguments, **kwargs)
            calls.append(dict(arguments=arguments, frames=len(kwargs['input'].splitlines()),
                              seconds=time.monotonic() - started))
            return result

        identity = store._engine_identity
        with patch.object(upload, 'run_engine', side_effect=observe), patch.object(
                store, '_engine_identity', wraps=identity) as hashes:
            result = upload.prepare_upload(self.root, envelope(source, **values))
        self.assertEqual(len(calls), 2, 'Preparation must not start one JVM per exercise.')
        self.assertEqual(hashes.call_count, 2, 'Hash dependencies once before and once after the batch.')
        self.assertEqual(calls[0]['arguments'][-1], 'live.UploadInspector')
        self.assertEqual(calls[1]['arguments'][-2:], ['live.ExerciseValidator', '--batch'])
        self.assertEqual(calls[1]['frames'], expected_frames)
        for call in calls:
            self.assertIn('-Xmx256m', call['arguments'])
            self.assertIn('-XX:ActiveProcessorCount=2', call['arguments'])
        return result, calls

    def test_100_groups_prepare_publish_reload_in_two_jvms(self):
        source = BASE + ''.join(f'pred practice{i} {{some Node}}\n' for i in range(100))
        prepared, _ = self.measured_prepare(source, expected_frames=100)
        self.assertEqual(len(prepared['documents']), 100)
        self.assertEqual([doc['predicate'] for doc in prepared['documents']],
                         [f'practice{i}' for i in range(100)])
        with tempfile.TemporaryDirectory(prefix='publish-', dir=SCRATCH) as folder:
            root = Path(folder)
            fixture(root)
            metadata = [dict(predicate=doc['predicate'], title=doc['predicate'],
                             question='Require at least one Node.') for doc in prepared['documents']]
            snapshot = store.commit_upload(root, prepared, metadata, guard=permitted)
            self.assertEqual(snapshot.exercise_count, 101)
            loaded = store.load_store(root)
            self.assertEqual(loaded.catalogue, snapshot.catalogue)
            self.assertEqual(loaded.admin_uploads[0]['originalSource'], source)
            for doc in prepared['documents']:
                self.assertEqual(loaded.correct_pools[doc['id']], tuple(doc['oracleSolutions']))
                self.assertEqual(loaded.exercises[doc['id']]['predicate'], doc['predicate'])

    def test_100_groups_with_two_equivalent_variants_still_use_two_jvms(self):
        prepared, _ = self.measured_prepare(grouped(100, equivalent=True), expected_frames=100)
        self.assertEqual(len(prepared['documents']), 100)
        self.assertTrue(all(len(doc['oracleSolutions']) == 2 for doc in prepared['documents']))
        self.assertTrue(all(cert['result']['evaluatedCandidates'] == 2
                            for cert in prepared['certificates']))

    def test_100_distinct_source_spellings_share_one_batch_of_200_frames(self):
        prepared, _ = self.measured_prepare(grouped(100, spelling=True), expected_frames=200)
        self.assertEqual(len(prepared['documents']), 100)
        self.assertTrue(all(doc['oracleSolutions'] == ['some Node'] for doc in prepared['documents']))
        self.assertTrue(all(len(group['variants']) == 2 for group in prepared['witness']['groups']))
        self.assertTrue(all(cert['result']['evaluatedCandidates'] == 1
                            for cert in prepared['certificates']))

    def test_256_source_variants_are_typed_and_provenanced(self):
        source = BASE + ''.join(f'pred inv1C{i} {{some Node}}\n' for i in range(256))
        prepared, _ = self.measured_prepare(source, expected_frames=1)
        self.assertEqual(len(prepared['witness']['groups'][0]['variants']), 256)
        self.assertEqual(prepared['documents'][0]['oracleSolutions'], ['some Node'])

    def test_101_groups_and_257_variants_reject_before_any_jvm(self):
        sources = [grouped(101), BASE + ''.join(f'pred inv1C{i} {{some Node}}\n' for i in range(257))]
        for source in sources:
            with self.subTest(groups=source.count('pred ')), patch.object(upload, 'run_engine') as worker:
                with self.assertRaises(upload.UploadError):
                    upload.prepare_upload(self.root, envelope(source))
                worker.assert_not_called()

    def test_selected_names_source_order_and_exact_unselected_environment(self):
        retained = ('private pred inv7 { no Node }\r\n'
                    'pred inv7C0 {some Node}\r\npred inv7C1 {not no Node}\r\n'
                    'pred leaveThis { no Node }\r\n')
        source = ('module selections\r\n// preserve Unicode λ 🙂\r\nsig Node {}\r\n'
                  'pred CustomFirst {some Node}\r\n' + retained + 'pred Inv2C0 {some Node}\r\n')
        prepared, _ = self.measured_prepare(source, expected_frames=2,
                                            practicePredicates=['Inv2', 'CustomFirst'])
        self.assertEqual([doc['predicate'] for doc in prepared['documents']], ['CustomFirst', 'Inv2'])
        self.assertEqual(prepared['witness']['practicePredicates'], ['CustomFirst', 'Inv2'])
        self.assertTrue(upload.validate_witness(prepared['witness'], prepared['documents']))
        raw = source.encode('utf-8')
        for doc, group in zip(prepared['documents'], prepared['witness']['groups']):
            environment = doc['environmentBefore'] + doc['environmentAfter']
            self.assertIn(retained, environment)
            self.assertNotIn('pred CustomFirst', environment)
            self.assertNotIn('pred Inv2C0', environment)
            for side in ('before', 'after'):
                self.assertEqual(doc['environment' + side.title()].encode('utf-8'),
                                 b''.join(raw[start:end] for start, end in group[side + 'Spans']))

    def test_unknown_duplicate_and_split_variant_selection_rejected_without_jvm(self):
        for selection in (['unknown'], ['inv1', 'inv1'], ['inv1C0'], [], None, 'inv1', [True]):
            with self.subTest(selection=selection), patch.object(upload, 'run_engine') as worker:
                with self.assertRaises(upload.UploadError):
                    upload.prepare_upload(self.root, envelope(grouped(1), practicePredicates=selection))
                worker.assert_not_called()

    def test_full_source_checks_invalid_unselected_predicate(self):
        source = BASE + 'pred chosen {some Node}\npred unselected {unknownRelation}\n'
        with self.assertRaises(upload.UploadError):
            upload.prepare_upload(self.root, envelope(source, practicePredicates=['chosen']))

    def test_equivalent_unselected_group_is_retained_exactly(self):
        source = grouped(1) + 'pred inv2C0 {some Node}\npred inv2C1 {not no Node}\n'
        prepared, _ = self.measured_prepare(source, expected_frames=1, practicePredicates=['inv1'])
        environment = prepared['documents'][0]['environmentBefore'] + prepared['documents'][0]['environmentAfter']
        self.assertIn('pred inv2C0 {some Node}\npred inv2C1 {not no Node}', environment)

    def test_numbered_variant_mismatch_is_rejected_even_when_group_is_unselected(self):
        source = grouped(1) + 'pred ordinary {some Node}\npred inv2C0 {some Node}\npred inv2C1 {no Node}\n'
        for selection in (['inv1'], ['ordinary'], ['inv2']):
            with self.subTest(selection=selection), self.assertRaises(upload.UploadError):
                upload.prepare_upload(self.root, envelope(source, practicePredicates=selection))

    def test_ordinary_selected_predicate_can_use_retained_unselected_helper(self):
        retained = 'pred inv2C0 {some Node}\npred inv2C1 {not no Node}\n'
        source = BASE + retained + 'pred chosen {inv2C0}\n'
        prepared, _ = self.measured_prepare(source, expected_frames=1, practicePredicates=['chosen'])
        document = prepared['documents'][0]
        self.assertEqual(document['oracleSolutions'], ['inv2C0'])
        self.assertIn(retained, document['environmentBefore'] + document['environmentAfter'])

    def test_selection_witness_cannot_add_or_drop_retained_code(self):
        prepared = upload.prepare_upload(self.root, envelope(grouped(2), practicePredicates=['inv1']))
        for change in ('selection', 'environment', 'witness-field'):
            tampered = deepcopy(prepared)
            if change == 'selection':
                tampered['witness']['practicePredicates'] = ['inv2']
            elif change == 'environment':
                tampered['documents'][0]['environmentAfter'] = '\n'
            else:
                del tampered['witness']['practicePredicates']
            with self.subTest(change=change), self.assertRaises(upload.UploadError):
                upload.validate_witness(tampered['witness'], tampered['documents'])

    def test_large_repeated_environment_rejected_before_jvm_amplification(self):
        source = BASE + '/*' + 'unchanged ' * 12000 + '*/\n' + grouped(100).removeprefix(BASE)
        self.assertLess(len(source.encode('utf-8')), upload.MAX_SOURCE)
        with patch.object(upload, 'run_engine') as worker, self.assertRaisesRegex(upload.UploadError, 'large'):
            upload.prepare_upload(self.root, envelope(source))
        worker.assert_not_called()

    def test_invalid_final_group_and_timeout_leave_database_unchanged(self):
        source = grouped(100) + 'pred inv100C1 {no Node}\n'
        with tempfile.TemporaryDirectory(prefix='rollback-', dir=SCRATCH) as folder:
            root = Path(folder)
            fixture(root)
            before = (root / store.DATABASE_RELATIVE).read_bytes()
            with self.assertRaises(upload.UploadError):
                upload.prepare_upload(self.root, envelope(source))
            self.assertEqual((root / store.DATABASE_RELATIVE).read_bytes(), before)
            self.assertEqual(store.load_store(root).exercise_count, 1)
        with patch.object(upload, 'run_engine', side_effect=subprocess.TimeoutExpired('java', 1)):
            with self.assertRaises(upload.UploadError):
                upload.prepare_upload(self.root, envelope(grouped(100)), timeout=1)

    def test_discovery_is_one_jvm_and_contains_only_group_metadata(self):
        source = grouped(100, equivalent=True)
        with patch.object(upload, 'run_engine', wraps=upload.run_engine) as worker:
            discovered = upload.discover_upload(self.root, envelope(source, practicePredicates=[]))
        self.assertEqual(worker.call_count, 1)
        self.assertEqual(len(discovered['groups']), 100)
        self.assertEqual(discovered['groups'][0]['variants'], ['inv1C0', 'inv1C1'])
        self.assertNotIn('some Node', json.dumps(discovered))
        for private in ('originalSource', 'documents', 'certificates', 'oracleSolutions'):
            self.assertNotIn(private, discovered)

    def test_discovery_does_not_claim_equivalence_before_selection_validation(self):
        source = BASE + 'pred inv1C0 {some Node}\npred inv1C1 {no Node}\n'
        discovered = upload.discover_upload(self.root, envelope(source, practicePredicates=[]))
        self.assertEqual(discovered['groups'][0]['variants'], ['inv1C0', 'inv1C1'])
        self.assertNotIn('variantEquivalence', discovered)
        with self.assertRaises(upload.UploadError):
            upload.prepare_upload(self.root, envelope(source, practicePredicates=['inv1']))


class UploadInspectorEquivalenceTests(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def request(source=None, groups=None, scope=5):
        return dict(source=source or grouped(2, equivalent=True), equivalenceScope=scope,
                    variantGroups=groups if groups is not None else [
                        ['inv1C0', 'inv1C1'], ['inv2C0', 'inv2C1']])

    def test_success_binds_every_original_numbered_group_and_bounds(self):
        response = inspect(self.request(scope=3))
        self.assertEqual(response['status'], 'ok')
        self.assertEqual(response['variantEquivalence'], dict(groupCount=2, variantCount=4,
            scope=3, bitwidth=5, maxSequence=3, minTrace=1, maxTrace=10,
            solver='SAT4J', moduleFacts=True, factsSatisfiable=True, equivalence='bounded'))

    def test_paired_options_and_exact_types_are_required(self):
        valid = self.request()
        invalid = [dict(source=valid['source'], equivalenceScope=5),
                   dict(source=valid['source'], variantGroups=valid['variantGroups']),
                   dict(valid, unknown=True)]
        invalid.extend(dict(valid, equivalenceScope=scope) for scope in (True, 0, 9, 5.0, '5', None))
        for request in invalid:
            with self.subTest(fields=request.keys(), scope=request.get('equivalenceScope')):
                self.assertEqual(inspect(request)['status'], 'rejected')

    def test_missing_unknown_duplicate_split_and_malformed_groups_are_rejected(self):
        source = grouped(2, equivalent=True) + 'pred ordinary {some Node}\npred helper[n: Node] {some n}\n'
        invalid = [[], [[]], ['inv1C0'], 'inv1C0', None,
                   [['inv1C0', 'inv1C1']],
                   [['inv1C0'], ['inv2C0', 'inv2C1']],
                   [['inv1C0', 'inv1C0'], ['inv2C0', 'inv2C1']],
                   [['inv1C0', 'inv1C1'], ['inv1C0', 'inv1C1'], ['inv2C0', 'inv2C1']],
                   [['inv1C0', 'unknown'], ['inv2C0', 'inv2C1']],
                   [['inv1C0', 'inv2C1'], ['inv2C0', 'inv1C1']],
                   [['ordinary'], ['inv1C0', 'inv1C1'], ['inv2C0', 'inv2C1']],
                   [['helper'], ['inv1C0', 'inv1C1'], ['inv2C0', 'inv2C1']],
                   [[True], ['inv1C0', 'inv1C1'], ['inv2C0', 'inv2C1']],
                   [['inv1C0', 'inv1C1'], ['inv2C0', 'inv2C1']]]
        # The final shape is valid; exceed the limit without a different source
        # to ensure protocol limits are checked before solver work.
        invalid[-1] = invalid[-1] * 51
        for groups in invalid:
            with self.subTest(groups=groups):
                request = dict(source=source, equivalenceScope=5, variantGroups=groups)
                self.assertEqual(inspect(request)['status'], 'rejected')

    def test_variant_budget_and_parameterless_predicate_kind_are_enforced(self):
        source = BASE + ''.join(f'pred inv1C{i} {{some Node}}\n' for i in range(257))
        payload = self.request(source, [[f'inv1C{i}' for i in range(257)]])
        self.assertEqual(inspect(payload)['status'], 'rejected')
        for declaration in ('pred inv1C0[n: Node] {some n}', 'fun inv1C0: set Node {Node}'):
            with self.subTest(declaration=declaration):
                payload = self.request(BASE + declaration, [['inv1C0']])
                self.assertEqual(inspect(payload)['status'], 'rejected')

    def test_fact_restricted_equivalence_passes_but_inconsistent_facts_do_not(self):
        source = ('sig Node {}\nfact {some Node}\npred inv1C0 {some Node}\n'
                  'pred inv1C1 {some Node or no Node}\n')
        response = inspect(self.request(source, [['inv1C0', 'inv1C1']]))
        self.assertEqual(response['status'], 'ok')
        self.assertTrue(response['variantEquivalence']['factsSatisfiable'])
        inconsistent = source.replace('fact {some Node}', 'fact {some Node and no Node}')
        self.assertEqual(inspect(self.request(inconsistent, [['inv1C0', 'inv1C1']]))['status'], 'rejected')

    def test_string_literals_cannot_make_equivalence_vacuous(self):
        bad = ('sig Node {}\nfact {#String = 1}\npred inv1C0 {"A" in String}\n'
               'pred inv1C1 {"B" in String and no Node}\n')
        self.assertEqual(inspect(self.request(bad, [['inv1C0', 'inv1C1']]))['status'], 'rejected')
        safe = bad.replace('"B" in String and no Node', 'some Node or no Node')
        self.assertEqual(inspect(self.request(safe, [['inv1C0', 'inv1C1']]))['status'], 'ok')

    def test_python_verifier_rejects_missing_extra_and_wrong_equivalence_projection(self):
        request = self.request()
        source = request['source']
        response = inspect(request)
        declarations = upload._inventory(source)
        upload._check_inspection(response, source, declarations, [],
                                variant_groups=request['variantGroups'], scope=5)
        invalid = []
        missing = deepcopy(response)
        del missing['variantEquivalence']
        invalid.append(missing)
        for key, value in response['variantEquivalence'].items():
            wrong = deepcopy(response)
            wrong['variantEquivalence'][key] = False if type(value) is bool else None
            invalid.append(wrong)
        extra = deepcopy(response)
        extra['variantEquivalence']['unregistered'] = True
        invalid.append(extra)
        wrong_type = deepcopy(response)
        wrong_type['variantEquivalence']['scope'] = 5.0
        invalid.append(wrong_type)
        for changed in invalid:
            with self.subTest(projection=changed.get('variantEquivalence')), self.assertRaises(upload.UploadError):
                upload._check_inspection(changed, source, declarations, [],
                                        variant_groups=request['variantGroups'], scope=5)


if __name__ == '__main__':
    unittest.main()
