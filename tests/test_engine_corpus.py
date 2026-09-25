"""Finite, real-JVM regression coverage for every imported exercise.

Only exercise IDs and generic assertions are printed; private model bodies are
never included in test names, assertion output, or subprocess error output.
"""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import subprocess
import unittest

from server import model


ROOT = Path(__file__).resolve().parents[1]
COMMAND = ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
           str(ROOT / 'build/engine/classes') + ':' + str(ROOT / 'vendor/acgn/lib/*'),
           'live.LiveFeedback']
METRIC = 'acgn-fast-rewrite-canonical-distance'
SAFE_PATH = re.compile(r'(?:temporal|matrix|quantifier|normalForm\[\d+\]\.(?:matrix|quantifier)(?:\[\d+\])?)(?:\.child\[\d+\])*\Z')


def invoke(student_source, oracle_source, predicate='target'):
    """Never include request contents or raw process streams in exception text."""
    payload = {'studentSource': student_source, 'oracleSource': oracle_source,
               'predicate': predicate}
    try:
        run = subprocess.run(COMMAND, input=json.dumps(payload), text=True,
                             capture_output=True, timeout=12, cwd=ROOT, check=False)
    except subprocess.TimeoutExpired:
        raise AssertionError('Real JVM feedback timed out') from None
    if run.returncode:
        raise AssertionError('Real JVM feedback exited unsuccessfully')
    if run.stderr:
        raise AssertionError('Engine emitted unexpected stderr')
    try:
        return json.loads(run.stdout)
    except ValueError:
        raise AssertionError('Engine response was not exactly one JSON object') from None


def case(record, self_match):
    reference = model(record, record['oracleBody'])
    learner = reference if self_match else model(record, record['starter'])
    try:
        return record['id'], self_match, invoke(learner, reference, record['predicate']), None
    except (AssertionError, OSError) as error:
        return record['id'], self_match, None, type(error).__name__


class EngineCorpusTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (ROOT / 'build/engine/classes/live/LiveFeedback.class').is_file():
            raise AssertionError('Build the actual JVM engine before running closure tests')
        cls.records = json.loads((ROOT / 'exercises/catalogue.json').read_text())['exercises']
        tasks = [(record, self_match) for record in cls.records for self_match in (False, True)]
        with ThreadPoolExecutor(max_workers=4) as pool:
            cls.results = list(pool.map(lambda args: case(*args), tasks))

    def assert_valid_feedback(self, result):
        self.assertEqual(result.get('status'), 'ok', 'Engine did not normalize and compare this fixture')
        self.assertEqual(result['metric'], METRIC)
        self.assertIs(type(result['distance']), int)
        self.assertGreaterEqual(result['distance'], 0)
        self.assertEqual(set(result['breakdown']), {'temporal', 'quantifier', 'matrix'})
        self.assertEqual(result['distance'], sum(result['breakdown'].values()))
        self.assertEqual(result['distance'], sum(op['cost'] for op in result['operations']))
        self.assertEqual(result['trace']['cost'], result['distance'])
        self.assertTrue(result['trace']['matchesDistance'])
        self.assertFalse(result['trace']['certifiedOptimalScript'])
        self.assertTrue(result['trace']['matrixReplayVerified'])
        self.assertIsInstance(result['canonicalForm'], list)
        self.assertTrue(all(isinstance(line, str) for line in result['canonicalForm']))
        self.assertEqual(result['diagnostics'], [])
        forbidden = {'oracleSource', 'oracleBody', 'originalSource', 'oracleCanonicalForm',
                     'target', 'source', 'before', 'after', 'targetLabel', 'targetSummary'}
        self.assertTrue(forbidden.isdisjoint(result))
        for operation in result['operations']:
            required = {'kind', 'component', 'path', 'cost', 'aggregate', 'description'}
            enrichment = {'sourceTerm', 'sourceOperator', 'sourceNodeKind', 'sourceRole',
                          'replacementOperator', 'action', 'reason', 'nextStep'}
            self.assertTrue(required.issubset(operation))
            self.assertTrue(set(operation).issubset(required | enrichment))
            self.assertIn(operation['kind'], {'insert', 'delete', 'replace', 'modify', 'component-edit'})
            self.assertIn(operation['component'], {'temporal', 'quantifier', 'matrix'})
            self.assertTrue(SAFE_PATH.fullmatch(operation['path']), 'Operation path violated its whitelist')
            self.assertGreater(operation['cost'], 0)
            if operation['aggregate']:
                self.assertEqual(operation['kind'], 'component-edit')
                self.assertEqual(operation['component'], 'temporal')
            else:
                self.assertEqual(operation['cost'], 1)
            if operation['component'] == 'matrix':
                self.assertIn(operation['sourceRole'], {'affected', 'insertion-anchor'})
                self.assertIsInstance(operation['sourceTerm'], str)
                self.assertTrue(operation['sourceTerm'])
                self.assertTrue(operation['action'])
            if 'replacementOperator' in operation:
                self.assertIn(operation['kind'], {'replace', 'modify'})

    def test_all_181_starter_comparisons(self):
        self.assertEqual(len(self.records), 181, 'The registered exercise universe changed')
        for identifier, self_match, result, error in self.results:
            if self_match:
                continue
            with self.subTest(exercise=identifier):
                self.assertIsNone(error, 'Real JVM case did not complete')
                self.assert_valid_feedback(result)

    def test_all_181_reference_self_matches(self):
        for identifier, self_match, result, error in self.results:
            if not self_match:
                continue
            with self.subTest(exercise=identifier):
                self.assertIsNone(error, 'Real JVM case did not complete')
                self.assert_valid_feedback(result)
                self.assertEqual(result['distance'], 0)
                self.assertEqual(result['operations'], [])

    def test_known_canonical_distances(self):
        env = 'sig A { r: set A }\npred target { '
        def compare(a, b):
            result = invoke(env + a + '\n}', env + b + '\n}')
            self.assert_valid_feedback(result)
            return result
        self.assertEqual(compare('no A', 'some A')['distance'], 1)
        self.assertEqual(compare('all x: A | x in A', 'all y: A | y in A')['distance'], 0)
        self.assertEqual(compare('some A and no r', 'no r and some A')['distance'], 0)
        self.assertEqual(compare('some A and some A', 'some A')['distance'], 0)
        self.assertEqual(compare('some A', 'no A')['distance'], 1)
        self.assertEqual(compare('all x: A | some x.r', 'some x: A | some x.r')['distance'], 1)

    def test_call_identity_trace_is_reconstructed(self):
        env = 'sig A {}\npred p { some A }\npred q { no A }\npred target { '
        result = invoke(env + 'p\n}', env + 'q\n}')
        self.assert_valid_feedback(result)
        self.assertEqual(result['distance'], 1)
        self.assertFalse(result['trace']['hasAggregates'])
        self.assertTrue(result['trace']['matrixReplayVerified'])
        self.assertEqual(result['operations'][0]['kind'], 'replace')
        self.assertIn('p', result['operations'][0]['sourceTerm'])

    def test_reference_labels_and_errors_never_cross_wire(self):
        sentinel = 'PRIVATE_REFERENCE_SENTINEL_49578'
        learner = 'sig A {}\npred target { some A }'
        reference = 'sig A {}\nsig ' + sentinel + ' {}\npred target { some ' + sentinel + ' }'
        result = invoke(learner, reference)
        self.assert_valid_feedback(result)
        self.assertNotIn(sentinel, json.dumps(result), 'Reference label escaped public schema')
        malformed = invoke(learner, reference + '\n' + sentinel + ' invalid }')
        self.assertEqual(malformed['status'], 'engine_error')
        self.assertNotIn(sentinel, json.dumps(malformed), 'Reference parse error escaped public schema')
        self.assertNotIn('filename', json.dumps(malformed))
        self.assertNotIn('/tmp/', json.dumps(malformed))

    def test_learner_syntax_and_type_errors_are_positioned(self):
        reference = 'sig A {}\npred target { some A }'
        for body in ('some (', 'some A and A'):
            with self.subTest(kind='syntax' if body == 'some (' else 'type'):
                result = invoke('sig A {}\npred target { ' + body + ' }', reference)
                self.assertEqual(result['status'], 'invalid')
                self.assertGreater(result['diagnostics'][0]['line'], 0)
                self.assertGreater(result['diagnostics'][0]['column'], 0)
                self.assertNotIn('distance', result)


if __name__ == '__main__':
    unittest.main(verbosity=2)
