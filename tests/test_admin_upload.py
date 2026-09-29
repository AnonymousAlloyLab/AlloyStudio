"""Real Alloy upload preparation, source witness and hostile projection fixtures."""
from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import admin_upload
from admin_upload import GENERATED_STARTER, UploadError, prepare_upload, validate_witness
from runtime_dependencies import runtime_classpath

ROOT = Path(__file__).resolve().parents[1]
BASE = 'sig Node {}\n'


def envelope(source, **values):
    return dict(source=source, filename='lesson.als', modelId='lesson', **values)


def prepared(source, **values):
    return prepare_upload(ROOT, envelope(source, **values))


def inspect(source, *, options=()):
    run = subprocess.run(['java', *options, '-Xmx256m', '-XX:ActiveProcessorCount=2',
                          '-cp', runtime_classpath(ROOT), 'live.UploadInspector'],
                         input=json.dumps({'source': source}), capture_output=True,
                         text=True, encoding='utf-8', timeout=30, check=False)
    if run.returncode or run.stderr:
        raise AssertionError('Upload inspector violated its sanitized response boundary')
    return json.loads(run.stdout)


class AdminUploadTests(unittest.TestCase):
    def rejected(self, source, **values):
        with self.assertRaises(UploadError) as context:
            prepared(source, **values)
        self.assertNotIn(source, str(context.exception))
        return str(context.exception)

    def test_group_names_numeric_primary_and_source_group_order(self):
        source = ('module retained\n' + BASE + 'pred Inv2C10 {not no Node}\n'
                  'pred inv1C3 {no Node}\npred Inv2c2 {some Node}\n')
        result = prepared(source)
        self.assertEqual([item['predicate'] for item in result['documents']], ['Inv2', 'inv1'])
        self.assertEqual(result['documents'][0]['oracleSolutions'], ['some Node', 'not no Node'])
        self.assertEqual([item['name'] for item in result['witness']['groups'][0]['variants']], ['Inv2c2', 'Inv2C10'])
        self.assertEqual(result['documents'][0]['starter'], GENERATED_STARTER)
        self.assertEqual(result['documents'][0]['predicateHeader'], 'pred Inv2 ')
        self.assertEqual(result['witness']['originalSource'], source)
        self.assertTrue(validate_witness(result['witness'], result['documents']))

    def test_existing_empty_starter_and_safe_anonymous_command_preserved(self):
        source = BASE + 'pred inv1 /* original header */ [] { }\npred inv1C0 {some Node}\nrun { inv1 }\n'
        result = prepared(source)
        document = result['documents'][0]
        self.assertEqual(document['starter'], ' ')
        self.assertIn('run { inv1 }', document['environmentAfter'])
        self.assertEqual(result['witness']['groups'][0]['starter']['name'], 'inv1')
        self.assertEqual(document['oracleSolutions'], ['some Node'])

    def test_standalone_name_and_helpers_are_preserved(self):
        source = (BASE + 'pred helper[n: Node] {some n}\nfun nodes: set Node {Node}\n'
                  'pred customRule { all n: nodes | helper[n] }\nrun {customRule}\n')
        result = prepared(source)
        self.assertEqual(len(result['documents']), 1)
        document = result['documents'][0]
        self.assertEqual(document['predicate'], 'customRule')
        self.assertEqual(document['oracleSolutions'], [' all n: nodes | helper[n] '])
        self.assertIn('pred helper[n: Node] {some n}\nfun nodes: set Node {Node}\n', document['environmentBefore'])
        self.assertEqual(document['environmentAfter'], '\nrun {customRule}\n')

    def test_multiple_standalones_have_independent_private_solutions(self):
        result = prepared(BASE + 'pred firstRule {some Node}\npred secondRule {no Node}\n')
        self.assertEqual([item['predicate'] for item in result['documents']], ['firstRule', 'secondRule'])
        for document in result['documents']:
            environment = document['environmentBefore'] + document['environmentAfter']
            self.assertNotIn('pred firstRule', environment)
            self.assertNotIn('pred secondRule', environment)

    def test_crlf_unicode_private_modifier_and_every_retained_byte(self):
        source = ('module keep\r\n// 🎯 retained Unicode α\r\nsig Node{}\r\n'
                  'private /* modifier */ pred Inv1C0 /* header */ [] {\r\n some Node // α\r\n}\r\n'
                  '// retained middle\r\nprivate pred Inv1C1 {not no Node}\r\nsig Other{}\r\n')
        result = prepared(source)
        document, witness = result['documents'][0], result['witness']
        group = witness['groups'][0]
        raw = source.encode('utf-8')
        self.assertEqual(witness['originalSource'].encode('utf-8'), raw)
        self.assertEqual(document['oracleSolutions'][0], '\r\n some Node // α\r\n')
        for side in ('before', 'after'):
            reconstructed = b''.join(raw[start:end] for start, end in group[side + 'Spans'])
            self.assertEqual(reconstructed.decode('utf-8'), document['environment' + side.title()])
        for variant in group['variants']:
            removed = raw[variant['startByte']:variant['endByte']]
            body = raw[variant['bodyStartByte']:variant['bodyEndByte']]
            self.assertTrue(removed.startswith(b'private'))
            self.assertEqual(variant['bodySha256'], hashlib.sha256(body).hexdigest())
        self.assertEqual(document['environmentAfter'], '\r\n// retained middle\r\n\r\nsig Other{}\r\n')
        self.assertNotIn('private', document['environmentAfter'])

    def test_bundled_ordering_import_is_supported(self):
        result = prepared('open util/ordering[Node]\n' + BASE + 'pred target {some first}\n')
        self.assertIn('open util/ordering[Node]', result['documents'][0]['environmentBefore'])

    def test_token_duplicates_keep_each_original_declaration_witness(self):
        result = prepared(BASE + 'pred inv1C0 {some Node}\npred inv1C1 { some /* comment */ Node }\n')
        self.assertEqual(result['documents'][0]['oracleSolutions'], ['some Node'])
        variants = result['witness']['groups'][0]['variants']
        self.assertEqual([item['solutionIndex'] for item in variants], [0, 0])
        self.assertNotEqual(variants[0]['bodySha256'], variants[1]['bodySha256'])
        self.assertEqual(result['certificates'][0]['result']['oracleCount'], 1)

    def test_every_variant_is_checked_after_an_earlier_equivalent_body(self):
        for final in ('no Node', 'some PrivateMissingName'):
            with self.subTest(final=final):
                self.rejected(BASE + 'pred inv1C0 {some Node}\npred inv1C1 {not no Node}\npred inv1C2 {' + final + '}\n')

    def test_fact_sensitive_agreement_and_nonvacuity(self):
        result = prepared(BASE + 'fact {one Node}\npred inv1C0 {one Node}\npred inv1C1 {some Node}\n')
        self.assertTrue(result['certificates'][0]['result']['factsSatisfiable'])
        self.rejected(BASE + 'fact {some Node and no Node}\npred inv1C0 {some Node}\npred inv1C1 {no Node}\n')

    def test_scope_five_pass_and_scope_six_constructed_counterexample(self):
        source = BASE + 'pred inv1C0 {#Node > 5}\npred inv1C1 {some Node and no Node}\n'
        self.assertEqual(prepared(source)['certificates'][0]['result']['scope'], 5)
        self.rejected(source, equivalenceScope=6)

    def test_duplicate_overloaded_mixed_case_and_duplicate_variant_id(self):
        sources = [
            'pred inv1C0 {some Node}\npred inv1C0 {some Node}',
            'pred rule {some Node}\npred rule[n: Node] {some n}',
            'pred Inv1C0 {some Node}\npred inv1C1 {some Node}',
            'pred inv1C0 {some Node}\npred inv1c0 {some Node}',
            'pred Inv1C0 {some Node}\npred inv1 {some Node}',
            'pred inv1C0 {some Node}\nfun inv1: set Node {Node}',
            'pred inv1000000C0 {some Node}',
            'pred inv1C1000000 {some Node}',
        ]
        for source in sources:
            with self.subTest(source=source):
                self.rejected(BASE + source)

    def test_macro_and_recursive_dependencies_fail_closed(self):
        for suffix in ('let hidden = some Node\npred target {some Node}',
                       'pred target {target}',
                       'pred h[n: Node] {h[n]}\npred target {some Node}'):
            with self.subTest(suffix=suffix):
                self.rejected(BASE + suffix)

    def test_parameterized_variant_cannot_be_silently_excluded_from_group(self):
        # Silently treating C0 as a helper previously let only C1 be checked,
        # despite the administrator marking both as required oracle variants.
        for declaration in ('pred inv1C0[n:Node] {no n}', 'fun inv1C0: set Node {none}'):
            with self.subTest(declaration=declaration):
                self.rejected(BASE + declaration + '\npred inv1C1 {some Node}\n')

    def test_private_run_assert_fact_and_retained_helper_references_rejected(self):
        variants = 'pred inv1C0 {some Node}\npred inv1C1 {not no Node}\n'
        for suffix in ('run inv1C0', 'run {inv1C0}', 'assert checked {inv1C0}',
                       'fact {inv1C0}', 'pred helper[n:Node] {inv1C0}',
                       'pred inv1 {inv1C0}'):
            with self.subTest(suffix=suffix):
                self.rejected(BASE + variants + suffix + '\n')

    def test_oracle_aliases_and_transitive_starter_dependence_rejected(self):
        self.rejected(BASE + 'pred inv1C0 {some Node}\npred inv1C1 {inv1C0}\n')
        self.rejected(BASE + 'pred inv1 {some Node}\npred helper[n:Node] {inv1}\n'
                      'pred inv1C0 {all n:Node|helper[n]}\n')
        self.rejected(BASE + 'pred inv1 {}\npred inv1C0 {some Node}\n'
                      'pred inv2 {some Node}\npred helper[n:Node] {inv2}\npred inv2C0 {some Node}\n'
                      'pred third {all n:Node|helper[n]}\n')

    def test_external_module_is_rejected_by_actual_inspector(self):
        with tempfile.TemporaryDirectory(prefix='alloy-upload-external-') as folder:
            Path(folder, 'private_dependency.als').write_text('module private_dependency\npred hidden {some univ}\n')
            result = inspect('open private_dependency\n' + BASE + 'pred target {hidden}\n',
                             options=('-Djava.io.tmpdir=' + folder,))
            self.assertEqual(result, {'status': 'rejected', 'code': 'UNSUPPORTED_EXTERNAL_MODULE'})

    def test_inspector_duplicate_names_and_real_utf8_spans(self):
        result = inspect(BASE + 'pred rule {some Node}\npred rule[n:Node]{some n}')
        self.assertEqual(result, {'status': 'rejected', 'code': 'DUPLICATE_DECLARATION'})
        source = '// 🎯\r\n' + BASE + 'private pred target {some Node}\nrun {target}'
        result = inspect(source)
        self.assertEqual(result['status'], 'ok')
        self.assertEqual([row['name'] for row in result['declarations']], ['target'])
        self.assertEqual(result['commandCalls'], ['target'])
        row = result['declarations'][0]
        self.assertEqual(source.encode()[row['startByte']:row['endByte']], b'private pred target {some Node}')

    def test_witness_rejects_source_span_hash_type_and_document_mutations(self):
        result = prepared(BASE + 'pred inv1C0 {some Node}\npred inv1C1 {not no Node}\n')
        mutations = [
            lambda w: w.update(originalSource=w['originalSource'] + '\n'),
            lambda w: w.update(sourceSha256='0' * 64),
            lambda w: w.update(version=True),
            lambda w: w['groups'][0].update(insertionByte=True),
            lambda w: w['groups'][0]['variants'][0].update(bodyStartByte=0),
            lambda w: w['groups'][0]['variants'][0].update(bodySha256='0' * 64),
            lambda w: w['groups'][0]['variants'][0].update(solutionIndex=False),
            lambda w: w['groups'][0].update(afterSpans=[]),
            lambda w: w['groups'][0]['variants'].reverse(),
            lambda w: w.update(extra='forged'),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(witness_mutation=index):
                changed = copy.deepcopy(result['witness'])
                mutate(changed)
                with self.assertRaises(UploadError):
                    validate_witness(changed, result['documents'])
        mutations = [lambda d: d.update(predicate='changed'), lambda d: d.update(starter='no Node'),
                     lambda d: d.update(environmentAfter=''), lambda d: d['oracleSolutions'].reverse(),
                     lambda d: d.update(equivalenceScope=True), lambda d: d.update(extra='forged')]
        for index, mutate in enumerate(mutations):
            with self.subTest(document_mutation=index):
                changed = copy.deepcopy(result['documents'])
                mutate(changed[0])
                with self.assertRaises(UploadError):
                    validate_witness(result['witness'], changed)

    def test_reviewed_title_and_question_are_the_only_mutable_fields(self):
        result = prepared(BASE + 'pred arbitrary {some Node}\n')
        documents = copy.deepcopy(result['documents'])
        documents[0].update(title='A reviewed title', description='A reviewed question.')
        self.assertTrue(validate_witness(result['witness'], documents))
        for value in ('', 'x' * 257, '\ud800'):
            documents[0]['title'] = value
            with self.assertRaises(UploadError):
                validate_witness(result['witness'], documents)

    def test_envelope_and_fixed_data_bounds_rejected_before_worker(self):
        good = envelope(BASE + 'pred target {some Node}')
        changes = [dict(extra=True), dict(source=''), dict(source='\0'), dict(source='\ud800'),
                   dict(source='x' * 262145), dict(filename='../file.als'), dict(filename='x\\a.als'),
                   dict(filename='file.txt'), dict(modelId='../secret'), dict(equivalenceScope=True),
                   dict(equivalenceScope=0), dict(equivalenceScope=9)]
        with mock.patch.object(admin_upload, '_worker', side_effect=AssertionError('Worker must not run')):
            for values in changes:
                with self.subTest(values=list(values)):
                    with self.assertRaises(UploadError):
                        prepare_upload(ROOT, dict(good, **values))
            for source in (BASE + ''.join(f'pred p{i} {{some Node}}\n' for i in range(9)),
                           BASE + ''.join(f'pred inv1C{i} {{some Node}}\n' for i in range(65)),
                           BASE + 'pred target {some Node' + ' ' * 8193 + '}',
                           BASE + 'pred onlyHelper[n:Node] {some n}'):
                with self.assertRaises(UploadError):
                    prepared(source)

    def test_total_deadline_and_engine_replacement_fail_closed(self):
        source = BASE + 'pred target {some Node}'
        for timeout in (0, -1, 61, True, float('nan')):
            with self.assertRaises(UploadError):
                prepare_upload(ROOT, envelope(source), timeout=timeout)
        with self.assertRaises(UploadError):
            prepare_upload(ROOT, envelope(source), timeout=0.000001)
        from exercise_store import _engine_identity
        actual = _engine_identity(runtime_classpath(ROOT))
        changed = dict(actual, engineSha256='0' * 64)
        # Preparation entry, certificate start/end, preparation final check.
        with mock.patch('exercise_store._engine_identity', side_effect=[actual, actual, actual, changed]):
            with self.assertRaisesRegex(UploadError, 'engine changed'):
                prepared(source)

    def test_worker_timeout_and_private_failure_are_sanitized(self):
        source = BASE + 'pred secretOracle {some Node}'
        with mock.patch('admin_upload.subprocess.run', side_effect=subprocess.TimeoutExpired('private/path', 1)):
            with self.assertRaises(UploadError) as context:
                prepared(source)
            self.assertNotIn('private/path', str(context.exception))
        failed = subprocess.CompletedProcess([], 1, '{"status":"rejected","code":"private source text"}', 'private path')
        with mock.patch('admin_upload.subprocess.run', return_value=failed):
            with self.assertRaises(UploadError) as context:
                prepared(source)
            self.assertNotIn('private source text', str(context.exception))
            self.assertNotIn('private path', str(context.exception))


if __name__ == '__main__':
    unittest.main()
