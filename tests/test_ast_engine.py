"""Real-JVM raw AST metric, atomic replay, source occurrence, and privacy tests."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CLASSPATH = os.pathsep.join((str(ROOT / 'build/engine/classes'), str(ROOT / 'vendor/acgn/lib/*')))
COMMAND = ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', CLASSPATH, 'live.LiveFeedback']
ENV = 'module ast_fixture\nsig A { r, s: set A }\n'
METRIC = 'acgn-raw-ast-zhang-shasha-distance'


def invoke(payload):
    try:
        result = subprocess.run(COMMAND, input=json.dumps(payload), text=True, capture_output=True,
                                cwd=ROOT, timeout=15, check=False)
    except subprocess.TimeoutExpired:
        raise AssertionError('Raw AST JVM request timed out') from None
    if result.returncode or result.stderr:
        raise AssertionError('Raw AST JVM request failed or emitted diagnostics')
    try:
        return json.loads(result.stdout)
    except ValueError:
        raise AssertionError('Raw AST JVM response was not one JSON object') from None


def source(body, environment=ENV):
    return environment + 'pred target {\n' + body + '\n}\n'


def compare(body, reference, environment=ENV, reference_environment=None, **extra):
    return invoke({'metric': 'ast', 'studentSource': source(body, environment),
                   'oracleSource': source(reference, reference_environment or environment),
                   'predicate': 'target', **extra})


def pool(body, references):
    return invoke({'metric': 'ast', 'studentSource': source(body), 'predicate': 'target',
                   'referencePrefix': ENV + 'pred target {\n', 'referenceSuffix': '\n}\n',
                   'referenceBodies': references})


def fragment(text, location):
    return [text.encode('utf-16-le')[r['start'] * 2:r['end'] * 2].decode('utf-16-le')
            for r in location['ranges']]


class AstEngineTests(unittest.TestCase):
    def assert_feedback(self, result):
        self.assertEqual(result.get('status'), 'ok', 'Raw AST fixture did not complete')
        self.assertEqual(result['metric'], METRIC)
        self.assertEqual(result['breakdown'], {'ast': result['distance']})
        self.assertEqual(result['canonicalForm'], [])
        self.assertGreater(result['astSize'], 0)
        self.assertNotIn('canonicalSize', result)
        self.assertTrue(result['trace']['astReplayVerified'])
        self.assertTrue(result['trace']['matchesDistance'])
        self.assertFalse(result['trace']['hasAggregates'])
        self.assertEqual(result['trace']['astTraceAlgorithm'], 'zhang-shasha-node-edit-v1')
        self.assertEqual(result['trace']['cost'], result['distance'])
        self.assertEqual(len(result['operations']), result['distance'])
        self.assertEqual(sum(result['operationSummary'].values()), result['distance'])
        self.assertEqual(result['diagnostics'], [])
        for operation in result['operations']:
            self.assertEqual(operation['component'], 'ast')
            self.assertEqual(operation['cost'], 1)
            self.assertFalse(operation['aggregate'])
            self.assertRegex(operation['path'], r'^ast\[[1-9][0-9]*\]$')
            self.assertIn(operation['kind'], {'insert', 'delete', 'replace'})
            self.assertEqual(operation['canonicalLocation']['status'], 'unavailable')
            self.assertEqual(operation['canonicalLocation']['ranges'], [])
            location = operation['sourceLocation']
            self.assertEqual(location['offsetEncoding'], 'utf-16')
            self.assertEqual(location['coordinateSystem'], 'module')
            self.assertIn(location['status'], {'located', 'unavailable'})
            self.assertEqual(len(location['ranges']), int(location['status'] == 'located'))
            self.assertEqual(location['precision'], 'node' if location['ranges'] else 'related')
            if operation['kind'] == 'insert':
                self.assertEqual(operation['sourceRole'], 'insertion-anchor')
                self.assertNotIn('replacementOperator', operation)
            for name in ('target', 'targetLabel', 'after', 'before', 'replacementExpression', 'oracleSource', 'oracleBody'):
                self.assertNotIn(name, operation)

    def test_exhaustive_small_tree_oracle_and_true_node_replay(self):
        with tempfile.TemporaryDirectory(prefix='ast-oracle-') as directory:
            compiled = subprocess.run(['javac', '-encoding', 'UTF-8', '--release', '17', '-cp', CLASSPATH,
                                       '-d', directory, str(ROOT / 'engine/test/RawAstTraceSelfTest.java')],
                                      capture_output=True, timeout=25)
            self.assertEqual(compiled.returncode, 0, 'Finite AST oracle did not compile')
            checked = subprocess.run(['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
                                      directory + os.pathsep + CLASSPATH, 'is.fivefivefive.CanDis.RawAstTraceSelfTest'],
                                     text=True, capture_output=True, timeout=30)
        self.assertEqual(checked.returncode, 0, 'Finite mapping oracle or private node replay failed')
        self.assertEqual(checked.stderr, '')
        self.assertIn('10404 exhaustive pairs', checked.stdout)

    def test_identity_and_explicit_canonical_mode_default(self):
        result = compare('some A', 'some A')
        self.assert_feedback(result)
        self.assertEqual(result['distance'], 0)
        payload = {'studentSource': source('some A'), 'oracleSource': source('some A'), 'predicate': 'target'}
        implicit, explicit = invoke(payload), invoke(dict(payload, metric='canonical'))
        self.assertEqual(implicit, explicit)
        self.assertEqual(implicit['metric'], 'acgn-fast-rewrite-canonical-distance')

    def test_invalid_metric_is_rejected_without_canonical_fallback(self):
        for metric in ('AST', 'raw', '', None, 0, False, [], {}):
            with self.subTest(metric=metric):
                result = compare('some A', 'some A', metric=metric)
                self.assertEqual(result['status'], 'invalid_request')
                self.assertEqual(result['diagnostics'][0]['code'], 'INVALID_METRIC')
                self.assertNotIn('distance', result)

    def test_operator_replacement_is_one_atomic_node(self):
        result = compare('no A', 'some A')
        self.assert_feedback(result)
        self.assertEqual(result['distance'], 1)
        op = result['operations'][0]
        self.assertEqual(op['kind'], 'replace')
        self.assertEqual(op['replacementOperator'], 'some')
        self.assertEqual(fragment(source('no A'), op['sourceLocation']), ['no A'])

    def test_raw_metric_keeps_order_names_and_duplicates(self):
        fixtures = [('some A and no A', 'no A and some A', 2),
                    ('all x:A | some x.r', 'all y:A | some y.r', 2),
                    ('some A and some A', 'some A', 4)]
        for body, reference, expected in fixtures:
            with self.subTest(expected=expected):
                result = compare(body, reference)
                self.assert_feedback(result)
                self.assertEqual(result['distance'], expected)
                self.assertEqual(compare(reference, body)['distance'], expected)
                self.assertEqual(compare(body, reference, metric='canonical')['distance'], 0)

    def test_operator_hints_use_the_shared_safe_presentation_vocabulary(self):
        for body, reference, expected in (
                ('some A => no r', 'some A <=> no r', 'iff'),
                ('A in A.r', 'A not in A.r', '!in'),
                ('#A = 1', '#A > 1', '>'),
                ('r in A -> some A', 'r in A -> one A', '->one')):
            with self.subTest(operator=expected):
                result = compare(body, reference)
                self.assert_feedback(result)
                replacements = [operation['replacementOperator'] for operation in result['operations']
                                if 'replacementOperator' in operation]
                self.assertIn(expected, replacements)

    def test_raw_parser_wrappers_are_counted_without_normalization(self):
        result = compare('some A', 'not some A')
        self.assert_feedback(result)
        self.assertEqual(result['distance'], 2)
        self.assertEqual(result['operationSummary'], {'insert': 2})

    def test_temporal_wrapper_insertion_and_deletion_use_node_cost(self):
        inserted, deleted = compare('some A', 'always some A'), compare('always some A', 'some A')
        for result in (inserted, deleted):
            self.assert_feedback(result)
            self.assertEqual(result['distance'], 1)
        self.assertEqual(inserted['operationSummary'], {'insert': 1})
        self.assertEqual(deleted['operationSummary'], {'delete': 1})

    def test_ast_quantifier_replacement_has_original_source(self):
        body = 'all x:A | some x.r'
        result = compare(body, 'some x:A | some x.r')
        self.assert_feedback(result)
        self.assertEqual(result['distance'], 1)
        op = result['operations'][0]
        self.assertEqual(op['replacementOperator'], 'some')
        self.assertEqual(fragment(source(body), op['sourceLocation']), [body])

    def test_repeated_leaf_locations_use_the_changed_branch(self):
        body = 'some A.r or lone A.r'
        result = compare(body, 'some A.r or lone r.A')
        self.assert_feedback(result)
        self.assertEqual(result['distance'], 2)
        start = source(body).index('lone A.r')
        for op in result['operations']:
            self.assertIn(op['sourceTerm'], ('A', 'r'))
            at = start + (5 if op['sourceTerm'] == 'A' else 7)
            self.assertEqual(op['sourceLocation']['ranges'], [{'start': at, 'end': at + 1}])

    def test_repeated_call_arguments_use_second_operand(self):
        env = ENV + 'pred helper[a,b:set A] { a in b }\n'
        body = 'helper[A,A]'
        result = compare(body, 'helper[A,none]', env)
        self.assert_feedback(result)
        self.assertEqual(result['distance'], 1)
        op = result['operations'][0]
        at = source(body, env).index(body) + 9
        self.assertEqual(op['sourceLocation']['ranges'], [{'start': at, 'end': at + 1}])

    def test_let_and_ite_keep_original_occurrences(self):
        body = 'let z=A | some z'
        result = compare(body, 'let z=A | no z')
        self.assert_feedback(result)
        self.assertEqual(fragment(source(body), result['operations'][0]['sourceLocation']), ['some z'])
        body = 'some ((some A) => A else none)'
        result = compare(body, 'some ((some A) => none else A)')
        self.assert_feedback(result)
        self.assertEqual({op['sourceTerm'] for op in result['operations']}, {'A', 'none'})
        self.assertEqual({op['sourceLocation']['ranges'][0]['start'] for op in result['operations']},
                         {source(body).index('=> A') + 3, source(body).index('else none') + 5})

    def test_comments_crlf_tabs_and_unicode_keep_utf16_positions(self):
        body = '// 😀 no A\r\n\tno /* 😀 */ A'
        result = compare(body, 'some A')
        self.assert_feedback(result)
        op = result['operations'][0]
        self.assertEqual(fragment(source(body), op['sourceLocation']), ['no /* 😀 */ A'])
        at = source(body).index('no /*')
        self.assertEqual(op['sourceLocation']['ranges'][0]['start'], len(source(body)[:at].encode('utf-16-le')) // 2)

    def test_insertions_reveal_only_visible_learner_context(self):
        body = 'no A'
        result = compare(body, 'no A and some r')
        self.assert_feedback(result)
        self.assertGreater(result['distance'], 1)
        for op in result['operations']:
            if op['kind'] == 'insert':
                self.assertEqual(fragment(source(body), op['sourceLocation']), ['no A'])
                self.assertIn('does not specify where', op['sourceLocation']['reason'])

    def test_private_references_names_calls_and_literals_never_leave_engine(self):
        secret = 'AST_PRIVATE_REFERENCE_SENTINEL'
        fixtures = [('some A', 'some ' + secret, ENV, ENV + 'sig ' + secret + ' {}\n'),
                    ('some "VISIBLE"', 'some "' + secret + '"', ENV, ENV),
                    ('known', secret, ENV + 'pred known { some A }\n', ENV + 'pred ' + secret + ' { no A }\n')]
        for body, reference, env, ref_env in fixtures:
            result = compare(body, reference, env, ref_env)
            self.assert_feedback(result)
            self.assertGreater(result['distance'], 0)
            self.assertNotIn(secret, json.dumps(result))

    def test_nearest_correct_pool_includes_oracle_and_uses_ast_metric(self):
        body, oracle = 'some A and no r', 'no r and some A'
        self.assertGreater(compare(body, oracle)['distance'], 0)
        result = pool(body, [oracle, body])
        self.assert_feedback(result)
        self.assertEqual(result['distance'], 0)
        self.assertEqual(result['comparison'], {'strategy': 'nearest-known-correct', 'poolSize': 2,
                                               'evaluatedCandidates': 2, 'complete': True})
        self.assertNotIn('selectedReference', result)

    def test_pool_ties_are_deterministic_and_all_candidates_are_checked(self):
        for references, operator in ((['no A', 'one A'], 'no'), (['one A', 'no A'], 'one')):
            result = pool('some A', references)
            self.assert_feedback(result)
            self.assertEqual(result['operations'][0]['replacementOperator'], operator)
            self.assertEqual(result['comparison']['evaluatedCandidates'], 2)
        failed = pool('some A', ['some A', 'some PRIVATE_LATE_REFERENCE'])
        self.assertEqual(failed['status'], 'engine_error')
        self.assertEqual(failed['diagnostics'][0]['code'], 'REFERENCE_POOL_UNAVAILABLE')
        self.assertNotIn('distance', failed)
        self.assertNotIn('comparison', failed)
        self.assertNotIn('PRIVATE_LATE_REFERENCE', json.dumps(failed))

    def test_largest_real_correct_pool_is_complete_including_oracle(self):
        from server import load_correct_pools, model
        encoded = (ROOT / 'exercises/catalogue.json').read_bytes()
        catalogue = json.loads(encoded)
        pools = load_correct_pools(ROOT, encoded, catalogue)
        exercise_id = max(pools, key=lambda key: len(pools[key]))
        record = next(record for record in catalogue['exercises'] if record['id'] == exercise_id)
        self.assertEqual(pools[exercise_id][-1], record['oracleBody'])
        result = invoke({'metric': 'ast', 'studentSource': model(record, record['oracleBody']),
                         'predicate': record['predicate'], 'referenceBodies': pools[exercise_id],
                         'referencePrefix': record['environmentBefore'] + record['predicateHeader'] + '{\n',
                         'referenceSuffix': '\n}' + record['environmentAfter']})
        self.assert_feedback(result)
        self.assertEqual(result['distance'], 0)
        self.assertGreater(len(pools[exercise_id]), 300)
        self.assertEqual(result['comparison']['evaluatedCandidates'], len(pools[exercise_id]))
        self.assertTrue(result['comparison']['complete'])

    def test_invalid_source_and_reference_fail_without_source_echo(self):
        for body in ('some (', 'some A and A', 'some PRIVATE_UNKNOWN_NAME'):
            result = compare(body, 'some A')
            self.assertEqual(result['status'], 'invalid')
            self.assertNotIn('distance', result)
            self.assertNotIn('PRIVATE_UNKNOWN_NAME', json.dumps(result))
        result = compare('some A', 'some PRIVATE_UNKNOWN_NAME')
        self.assertEqual(result['status'], 'engine_error')
        self.assertNotIn('PRIVATE_UNKNOWN_NAME', json.dumps(result))

    def test_ast_limits_do_not_publish_partial_pool_minimum(self):
        huge = ' and '.join(['some A'] * 400)
        result = compare(huge, 'some A')
        self.assertEqual(result['status'], 'unsupported')
        self.assertEqual(result['diagnostics'][0]['code'], 'AST_UNAVAILABLE')
        result = pool('some A', ['some A', huge])
        self.assertEqual(result['status'], 'engine_error')
        self.assertEqual(result['diagnostics'][0]['code'], 'REFERENCE_POOL_UNAVAILABLE')
        self.assertNotIn('distance', result)

    def test_invalid_reference_inputs(self):
        for references in ([], [''], [' '], [None], [1], ['some A'] * 4097):
            result = pool('some A', references)
            self.assertEqual(result['status'], 'invalid_request')
        result = invoke({'metric': 'ast', 'studentSource': source('some A'), 'predicate': 'target',
                         'oracleSource': source('some A'), 'referencePrefix': ENV + 'pred target {',
                         'referenceSuffix': '}', 'referenceBodies': ['some A']})
        self.assertEqual(result['status'], 'invalid_request')


class AstCorpusTests(unittest.TestCase):
    """Every shipped exercise, with both starter/oracle and identity comparisons."""
    assert_feedback = AstEngineTests.assert_feedback

    @classmethod
    def setUpClass(cls):
        from server import model
        records = json.loads((ROOT / 'exercises/catalogue.json').read_text())['exercises']
        def evaluate(task):
            record, identity = task
            payload = {'metric': 'ast', 'studentSource': model(record, record['oracleBody'] if identity else record['starter']),
                       'oracleSource': model(record, record['oracleBody']), 'predicate': record['predicate']}
            try:
                return record['id'], identity, invoke(payload), None
            except AssertionError:
                return record['id'], identity, None, 'JVM failure'
        with ThreadPoolExecutor(max_workers=4) as workers:
            cls.results = list(workers.map(evaluate, [(record, identity) for record in records for identity in (False, True)]))

    def test_all_shipped_asts_compare_and_replay(self):
        for exercise_id, identity, result, error in self.results:
            with self.subTest(exercise=exercise_id, identity=identity):
                self.assertIsNone(error)
                self.assert_feedback(result)
                if identity:
                    self.assertEqual(result['distance'], 0)
                    self.assertEqual(result['operations'], [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
