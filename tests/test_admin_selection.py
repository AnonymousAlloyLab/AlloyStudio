"""Real Alloy witnesses for bounded batch upload and explicit practice selection."""
from contextlib import contextmanager
from copy import deepcopy
import hashlib
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

    def test_100_parameterized_practices_keep_typed_slots_and_two_jvm_bound(self):
        source = BASE + ''.join(f'pred practice{i}[n: Node] {{some n}}\n' for i in range(100))
        prepared, _ = self.measured_prepare(source, expected_frames=100)
        self.assertEqual(len(prepared['documents']), 100)
        for index, document in enumerate(prepared['documents']):
            self.assertEqual(document['predicateHeader'], f'pred practice{index}[n: Node] ')
            self.assertEqual(document['oracleSolutions'], ['some n'])
        self.assertEqual(len(prepared['witness']['groups'][0]['siblingSlots']), 99)

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
            if doc['predicate'] == 'CustomFirst':
                self.assertNotIn('pred CustomFirst', environment)
            else:
                self.assertIn('pred CustomFirst {\n' + upload.GENERATED_STARTER + '\n}', environment)
                self.assertNotIn('pred CustomFirst {some Node}', environment)
            self.assertNotIn('pred Inv2C0', environment)
            for side in ('before', 'after'):
                pieces = [(start, raw[start:end]) for start, end in group[side + 'Spans']]
                pieces += [(slot['startByte'], (slot['header'] + '{\n' + upload.GENERATED_STARTER + '\n}').encode())
                           for slot in group.get('siblingSlots', [])
                           if (slot['startByte'] < group['insertionByte']) == (side == 'before')]
                self.assertEqual(doc['environment' + side.title()].encode('utf-8'),
                                 b''.join(value for _, value in sorted(pieces)))

    def test_selected_sibling_names_support_unchanged_retained_aggregate_without_answers(self):
        helper = 'private pred helper[n: Node] {some n}\n'
        aggregate = 'pred aggregate { firstRule and secondRule }\nassert combined {aggregate}\nrun aggregate\n'
        source = (BASE + helper + 'pred firstRule {all n: Node | helper[n]}\n'
                  'pred secondRule {no Node}\n' + aggregate)
        prepared, _ = self.measured_prepare(source, expected_frames=2,
                                            practicePredicates=['secondRule', 'firstRule'])
        self.assertEqual(prepared['witness']['version'], 2)
        self.assertEqual(prepared['witness']['practicePredicates'], ['firstRule', 'secondRule'])
        for document in prepared['documents']:
            environment = document['environmentBefore'] + document['environmentAfter']
            self.assertIn(helper, environment)
            self.assertIn(aggregate, environment)
            self.assertNotIn('all n: Node | helper[n]', environment)
            self.assertNotIn('pred secondRule {no Node}', environment)
            sibling = 'secondRule' if document['predicate'] == 'firstRule' else 'firstRule'
            self.assertIn('pred ' + sibling + ' {\n' + upload.GENERATED_STARTER + '\n}', environment)
        self.assertTrue(upload.validate_witness(prepared['witness'], prepared['documents']))

    def test_parameterized_standalone_preserves_fixed_header_body_and_unselected_helpers(self):
        helper = 'private pred helper[n: Node] {some n}\n'
        aggregate = 'pred everyNode {all n: Node | typed[n, Node]}\nrun everyNode\n'
        header = 'pred typed /* fixed interface */ [n: Node, nodes: set Node] '
        body = ' n in nodes and helper[n] // unchanged λ\n '
        source = 'module typed\n' + BASE + helper + header + '{' + body + '}\n' + aggregate
        prepared, _ = self.measured_prepare(source, expected_frames=1, practicePredicates=['typed'])
        document = prepared['documents'][0]
        self.assertEqual(document['predicate'], 'typed')
        self.assertEqual(document['predicateHeader'], header)
        self.assertEqual(document['oracleSolutions'], [body])
        environment = document['environmentBefore'] + document['environmentAfter']
        self.assertIn(helper, environment)
        self.assertIn(aggregate, environment)
        self.assertNotIn(body, environment)
        discovered = upload.discover_upload(self.root, envelope(source, practicePredicates=[]))
        metadata = next(group for group in discovered['groups'] if group['predicate'] == 'typed')
        self.assertEqual(metadata['predicateHeader'], header)
        self.assertNotIn(body, json.dumps(discovered, ensure_ascii=False))
        self.assertTrue(upload.validate_witness(prepared['witness'], prepared['documents']))

    def test_parameterized_sibling_slots_keep_their_exact_typed_interface(self):
        source = (BASE + 'pred first[n: Node] {some n}\n'
                  'pred second[n: Node, nodes: set Node] {n in nodes}\n'
                  'pred aggregate {all n: Node | first[n] and second[n, Node]}\n')
        prepared, _ = self.measured_prepare(source, expected_frames=2, practicePredicates=['first', 'second'])
        first, second = prepared['documents']
        self.assertIn('pred second[n: Node, nodes: set Node] {\n' + upload.GENERATED_STARTER + '\n}', first['environmentAfter'])
        self.assertIn('pred first[n: Node] {\n' + upload.GENERATED_STARTER + '\n}', second['environmentBefore'])
        self.assertEqual(first['oracleSolutions'], ['some n'])
        self.assertEqual(second['oracleSolutions'], ['n in nodes'])

    def test_parameterized_group_checks_every_variant_and_preserves_starter_interface(self):
        source = (BASE + 'pred inv1 /* fixed starter */ [n: Node] { }\n'
                  'pred inv1C7 [n : /* same domain */ Node] {not no n}\npred inv1C0[n: Node] {some n}\n')
        prepared, _ = self.measured_prepare(source, expected_frames=1, practicePredicates=['inv1'])
        document = prepared['documents'][0]
        self.assertEqual(document['predicateHeader'], 'pred inv1 /* fixed starter */ [n: Node] ')
        self.assertEqual(document['starter'], ' ')
        self.assertEqual(document['oracleSolutions'], ['some n', 'not no n'])
        self.assertEqual([entry['name'] for entry in prepared['witness']['groups'][0]['variants']], ['inv1C0', 'inv1C7'])
        for fragment in ('inv1C0', 'inv1C7'):
            self.assertNotIn(fragment, document['environmentBefore'] + document['environmentAfter'])

    def test_disjoint_grouped_parameters_and_relational_domains_are_supported(self):
        source = ('sig Node {edge: set Node}\n'
                  'pred inv1C0[disj a,b: Node, relation: Node -> Node] {a->b in relation}\n'
                  'pred inv1C1[disj a,b: Node, relation: Node -> Node] {not (a->b not in relation)}\n')
        prepared, _ = self.measured_prepare(source, expected_frames=1, practicePredicates=['inv1'])
        self.assertEqual(prepared['documents'][0]['predicateHeader'],
                         'pred inv1[disj a,b: Node, relation: Node -> Node] ')
        self.assertEqual(prepared['certificates'][0]['result']['evaluatedCandidates'], 2)

    def test_parameterized_group_mismatch_rejects_even_when_unselected(self):
        source = (BASE + 'pred ordinary {some Node}\npred inv1C0[n: Node] {some n}\n'
                  'pred inv1C1[n: Node] {no n}\n')
        for selection in (['ordinary'], ['inv1']):
            with self.subTest(selection=selection), self.assertRaises(upload.UploadError):
                upload.prepare_upload(self.root, envelope(source, practicePredicates=selection))

    def test_mismatched_parameter_names_domains_grouping_or_starter_reject_before_jvm(self):
        second_headers = ('m: Node', 'n: set Node', 'n: lone Node', 'n: Node, m: Node', '')
        sources = [BASE + 'pred inv1C0[n: Node] {some n}\npred inv1C1[' + header + '] {some Node}\n'
                   for header in second_headers]
        sources += [BASE + 'pred inv1[n: set Node] {}\npred inv1C0[n: Node] {some n}\n',
                    BASE + 'pred inv1C0[disj n,m: Node] {n != m}\npred inv1C1[n,m: Node] {n != m}\n',
                    BASE + 'pred inv1C0[n,m: Node] {n = m}\npred inv1C1[n: Node,m: Node] {n = m}\n']
        for source in sources:
            with self.subTest(source=source), patch.object(upload, 'run_engine') as worker:
                with self.assertRaisesRegex(upload.UploadError, 'matching parameter declarations'):
                    upload.prepare_upload(self.root, envelope(source))
                worker.assert_not_called()

    def test_fixed_typed_header_utf8_byte_limit_rejects_before_jvm(self):
        source = BASE + 'pred huge /*' + 'λ' * 4097 + '*/ [n: Node] {some n}\n'
        self.assertLess(len(source.encode('utf-8')), upload.MAX_SOURCE)
        with patch.object(upload, 'run_engine') as worker, self.assertRaises(upload.UploadError):
            upload.prepare_upload(self.root, envelope(source))
        worker.assert_not_called()

    def test_large_unselected_parameterized_helper_body_is_preserved_without_practice_cap(self):
        helper_body = 'some n /*' + ' unchanged' * 1000 + ' */'
        helper = 'private pred retained[n: Node] {' + helper_body + '}\n'
        source = BASE + helper + 'pred practice {some Node}\n'
        self.assertGreater(len(helper_body.encode()), upload.MAX_BODY)
        discovered = upload.discover_upload(self.root, envelope(source, practicePredicates=[]))
        self.assertEqual([group['predicate'] for group in discovered['groups']], ['retained', 'practice'])
        prepared, _ = self.measured_prepare(source, expected_frames=1, practicePredicates=['practice'])
        self.assertIn(helper, prepared['documents'][0]['environmentBefore'])
        self.assertTrue(upload.validate_witness(prepared['witness'], prepared['documents']))
        with patch.object(upload, 'run_engine') as worker, self.assertRaises(upload.UploadError):
            upload.prepare_upload(self.root, envelope(source, practicePredicates=['retained']))
        worker.assert_not_called()

    def test_large_unselected_ordinary_header_is_retained_without_discovery_header(self):
        for parameters in ('', '[n: Node]'):
            with self.subTest(parameters=parameters):
                header = 'pred retained /*' + ' unchanged' * 1000 + ' */ ' + parameters + ' '
                helper = header + '{some Node}\n'
                source = BASE + helper + 'pred practice {some Node}\n'
                self.assertGreater(len(header.encode()), upload.MAX_HEADER)
                discovered = upload.discover_upload(self.root, envelope(source, practicePredicates=[]))
                metadata = next(group for group in discovered['groups'] if group['predicate'] == 'retained')
                self.assertNotIn('predicateHeader', metadata)
                self.assertNotIn('unchanged', json.dumps(metadata))
                prepared, _ = self.measured_prepare(source, expected_frames=1, practicePredicates=['practice'])
                self.assertIn(helper, prepared['documents'][0]['environmentBefore'])
                self.assertTrue(upload.validate_witness(prepared['witness'], prepared['documents']))
                with patch.object(upload, 'run_engine') as worker, self.assertRaises(upload.UploadError):
                    upload.prepare_upload(self.root, envelope(source, practicePredicates=['retained']))
                worker.assert_not_called()

    def test_malformed_typed_headers_bodies_and_excess_parameters_are_rejected(self):
        sources = [BASE + 'pred invalid[n: Unknown] {some n}\n',
                   BASE + 'pred invalid[n: Node] {n + Node}\n',
                   BASE + 'pred invalid[n: Node {some n}\n',
                   BASE + 'pred invalid[' + ','.join('n' + str(index) for index in range(17)) + ': Node] {some Node}\n']
        for source in sources:
            with self.subTest(source=source), self.assertRaises(upload.UploadError):
                upload.prepare_upload(self.root, envelope(source))

    def test_hidden_c_dependency_names_are_actionable_without_disclosing_the_body(self):
        source = BASE + 'pred inv1C0 {some Node}\npred helper[n: Node] {inv1C0}\n'
        with self.assertRaisesRegex(upload.UploadError, r'helper calls hidden inv1C0.*Deselect') as failure:
            upload.prepare_upload(self.root, envelope(source, practicePredicates=['inv1']))
        self.assertNotIn('some Node', str(failure.exception))
        kept, _ = self.measured_prepare(source, expected_frames=1, practicePredicates=['helper'])
        self.assertIn('pred inv1C0 {some Node}', kept['documents'][0]['environmentBefore'])

    def test_model_facts_cannot_change_with_selected_predicates_even_via_a_helper(self):
        source = BASE + 'pred practice {some Node}\npred indirect {practice}\nfact {indirect}\n'
        with self.assertRaisesRegex(upload.UploadError, r'Model facts depend on practice practice'):
            upload.prepare_upload(self.root, envelope(source, practicePredicates=['practice']))

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


class UploadProjectionCompatibilityTests(unittest.TestCase):
    @staticmethod
    def inspected(source):
        return dict(status='ok', sourceSha256=hashlib.sha256(source.encode()).hexdigest(),
                    moduleName='this', declarations=[dict(row, calls=[]) for row in upload._inventory(source)],
                    factCalls=[], assertionCalls=[], commandCalls=[])

    def test_discovery_keeps_large_parameterized_helper_body_private_and_still_invokes_inspection(self):
        body = 'some n /*' + ' unchanged' * 1000 + ' */'
        helper = 'pred retained[n: Node] {' + body + '}\n'
        source = BASE + helper + 'pred practice {some Node}\n'
        with patch.object(upload, '_worker', return_value=self.inspected(source)) as inspector, patch.object(
                store, '_engine_identity', return_value={'engineSha256': '0' * 64}), patch.object(upload, 'run_engine') as process:
            discovered = upload.discover_upload(ROOT, envelope(source, practicePredicates=[]))
        process.assert_not_called()
        inspector.assert_called_once()
        self.assertEqual(inspector.call_args.args[1:3], ('live.UploadInspector', {'source': source}))
        self.assertNotIn(body, json.dumps(discovered))
        self.assertEqual(discovered['groups'][0]['predicateHeader'], 'pred retained[n: Node] ')
        documents, witness, _ = upload._plan(source, 'capacity.als', 'capacity', 5, ['practice'])
        self.assertIn(helper, documents[0]['environmentBefore'])
        self.assertTrue(upload.validate_witness(witness, documents))
        with self.assertRaises(upload.UploadError):
            upload._plan(source, 'capacity.als', 'capacity', 5, ['retained'])

    def test_discovery_omits_oversized_ordinary_headers_without_bypassing_full_inspection(self):
        for parameters in ('', '[n: Node]'):
            with self.subTest(parameters=parameters):
                helper = 'pred retained /*' + ' unchanged' * 1000 + ' */ ' + parameters + ' {some Node}\n'
                source = BASE + helper + 'pred practice {some Node}\n'
                with patch.object(upload, '_worker', return_value=self.inspected(source)) as inspector, patch.object(
                        store, '_engine_identity', return_value={'engineSha256': '0' * 64}), patch.object(upload, 'run_engine') as process:
                    discovered = upload.discover_upload(ROOT, envelope(source, practicePredicates=[]))
                process.assert_not_called()
                inspector.assert_called_once()
                self.assertEqual(inspector.call_args.args[2], {'source': source})
                self.assertNotIn('predicateHeader', discovered['groups'][0])
                self.assertNotIn('unchanged', json.dumps(discovered))
                documents, witness, _ = upload._plan(source, 'capacity.als', 'capacity', 5, ['practice'])
                self.assertEqual(documents[0]['environmentBefore'], BASE + helper)
                self.assertTrue(upload.validate_witness(witness, documents))
                with self.assertRaises(upload.UploadError):
                    upload._plan(source, 'capacity.als', 'capacity', 5, ['retained'])
                with patch.object(upload, '_worker', side_effect=upload.UploadError('Model inspection rejected.')) as rejected, patch.object(
                        store, '_engine_identity', return_value={'engineSha256': '0' * 64}), self.assertRaisesRegex(upload.UploadError, 'inspection rejected'):
                    upload.discover_upload(ROOT, envelope(source, practicePredicates=[]))
                rejected.assert_called_once()

    def test_unselected_legacy_parameterized_helper_keeps_large_body_and_header_exactly(self):
        body = 'some n /*' + ' λ' * 3000 + ' */'
        header = 'private pred retained /*' + ' λ' * 3000 + ' */ [n: Node] '
        helper = header + '{' + body + '}\n'
        source = BASE + helper + 'pred practice {some Node}\n'
        self.assertGreater(len(body.encode()), upload.MAX_BODY)
        self.assertGreater(len(header.encode()), upload.MAX_HEADER)
        for version in (1, 2):
            with self.subTest(version=version):
                documents, witness, _ = upload._plan(source, 'large.als', 'large', 5, ['practice'], projection_version=version)
                self.assertEqual(documents[0]['environmentBefore'], BASE + helper)
                self.assertTrue(upload.validate_witness(witness, documents))
        with self.assertRaises(upload.UploadError):
            upload._plan(source, 'large.als', 'large', 5, ['retained'])

    def test_frozen_version_one_witness_keeps_original_environment_and_validates_unchanged(self):
        # This is the deployed v1 representation, including its absence of
        # sibling slots. It must not be silently migrated by witness checking.
        source = BASE + 'pred first {some Node}\npred second {no Node}\n'
        witness = dict(version=1, originalSource=source,
            sourceSha256='0c0b0b87677180b34a2ffa2c5ba96321beb6473d645112eeb6505f6b3e4b65c1',
            filename='legacy.als', modelId='legacy', equivalenceScope=5, groups=[
                dict(exerciseId='legacy-first', predicate='first', kind='standalone', starter=None,
                     insertionByte=12, beforeSpans=[[0,12]], afterSpans=[[34,35],[56,57]], variants=[
                         dict(name='first', startByte=12, endByte=34, bodyStartByte=24, bodyEndByte=33,
                              bodySha256='46c4689086ce7f6dd4fdde4cd70a97755a57abbc497dde9329ff504a2aa1ae62', solutionIndex=0)]),
                dict(exerciseId='legacy-second', predicate='second', kind='standalone', starter=None,
                     insertionByte=35, beforeSpans=[[0,12],[34,35]], afterSpans=[[56,57]], variants=[
                         dict(name='second', startByte=35, endByte=56, bodyStartByte=48, bodyEndByte=55,
                              bodySha256='219bd6b5bbce3822e40051743805944bd7775e899352c9c250edf90d81e2adf8', solutionIndex=0)])])
        documents = [dict(id='legacy-' + name, title=name, group='legacy', predicate=name,
                         description='Complete the ' + name + ' predicate in legacy.',
                         environmentBefore=before, environmentAfter=after, predicateHeader='pred ' + name + ' ',
                         starter=upload.GENERATED_STARTER, oracleSolutions=[body], correctSolutions=[], equivalenceScope=5)
                     for name,before,after,body in [('first', BASE, '\n\n', 'some Node'),
                                                  ('second', BASE + '\n', '\n', 'no Node')]]
        before = deepcopy((witness, documents))
        self.assertTrue(upload.validate_witness(witness, documents))
        self.assertEqual((witness, documents), before)
        changed = deepcopy(witness)
        changed['version'] = 2
        with self.assertRaises(upload.UploadError):
            upload.validate_witness(changed, documents)

    def test_version_two_sibling_slot_spans_names_headers_and_environment_are_protected(self):
        source = BASE + 'pred first[n: Node] {some n}\npred second[n: Node] {no n}\n'
        documents, witness, _ = upload._plan(source, 'bound.als', 'bound', 5)
        self.assertEqual(witness['version'], 2)
        self.assertTrue(upload.validate_witness(witness, documents))
        mutations = [lambda w: w['groups'][0]['siblingSlots'][0].update(predicate='forged'),
                     lambda w: w['groups'][0]['siblingSlots'][0].update(header='pred second '),
                     lambda w: w['groups'][0]['siblingSlots'][0].update(startByte=True),
                     lambda w: w['groups'][0]['siblingSlots'].clear(),
                     lambda w: w.update(version=1)]
        for mutate in mutations:
            changed = deepcopy(witness)
            mutate(changed)
            with self.subTest(witness=changed), self.assertRaises(upload.UploadError):
                upload.validate_witness(changed, documents)
        changed_documents = deepcopy(documents)
        changed_documents[0]['environmentAfter'] = changed_documents[0]['environmentAfter'].replace(
            upload.GENERATED_STARTER, 'no n')
        with self.assertRaises(upload.UploadError):
            upload.validate_witness(witness, changed_documents)


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

    def test_variant_budget_and_explicit_predicate_kind_are_enforced(self):
        source = BASE + ''.join(f'pred inv1C{i} {{some Node}}\n' for i in range(257))
        payload = self.request(source, [[f'inv1C{i}' for i in range(257)]])
        self.assertEqual(inspect(payload)['status'], 'rejected')
        parameterized = self.request(BASE + 'pred inv1C0[n: Node] {some n}', [['inv1C0']])
        self.assertEqual(inspect(parameterized)['status'], 'ok')
        for declaration in ('pred Node.inv1C0 {some this}', 'fun inv1C0: set Node {Node}'):
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
