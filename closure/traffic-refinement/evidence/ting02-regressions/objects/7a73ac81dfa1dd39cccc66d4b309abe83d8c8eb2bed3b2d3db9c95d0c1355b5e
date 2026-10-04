"""Finite minimum-over-pool and hidden-candidate tests using the actual JVM.

The synthetic pool labels test selection mechanics. Corpus provenance determines
which predicates are admitted as known correct; these tests do not infer it from
canonical distance or claim unrestricted Alloy semantic equivalence.
"""
from concurrent.futures import ThreadPoolExecutor
import copy
import json
from pathlib import Path
import subprocess
import unittest

from server import model
from test_engine_corpus import COMMAND, invoke


ROOT = Path(__file__).resolve().parents[1]
ENV = 'sig A { r: set A }\n'


def pool_request(learner, bodies, environment=ENV):
    return {'studentSource': environment + 'pred target {\n' + learner + '\n}',
            'predicate': 'target', 'referenceBodies': bodies,
            'referencePrefix': environment + 'pred target {\n', 'referenceSuffix': '\n}'}


def run_request(request):
    try:
        result = subprocess.run(COMMAND, input=json.dumps(request), text=True,
                                capture_output=True, timeout=20, cwd=ROOT, check=False)
    except subprocess.TimeoutExpired:
        raise AssertionError('Complete pool evaluation timed out') from None
    if result.returncode or result.stderr:
        raise AssertionError('Pool engine failed or emitted unexpected diagnostics')
    try:
        return json.loads(result.stdout)
    except ValueError:
        raise AssertionError('Pool engine did not return one valid JSON object') from None


def pair(learner, reference):
    return invoke(ENV + 'pred target { ' + learner + ' }',
                  ENV + 'pred target { ' + reference + ' }')


class NearestCorrectTests(unittest.TestCase):
    def assert_complete(self, result, count):
        self.assertEqual(result.get('status'), 'ok', 'Pool comparison did not complete')
        self.assertEqual(result['comparison'], {'strategy': 'nearest-known-correct',
                         'poolSize': count, 'evaluatedCandidates': count, 'complete': True})
        self.assertEqual(result['distance'], sum(op['cost'] for op in result['operations']))
        self.assertTrue(result['trace']['matrixReplayVerified'])
        for key in ('selectedIndex', 'winnerIndex', 'candidateIndex', 'selectedId', 'referenceId',
                    'referenceBodies', 'oracleSource', 'oracleDistance', 'targetHash', 'targetSource'):
            self.assertNotIn(key, result)
            self.assertNotIn(key, result['comparison'])

    def test_minimum_matches_every_independent_pair_distance(self):
        bodies = ['no A', 'some A', 'all x:A | some x.r', 'some r']
        for learner in ('one A', 'no r', 'all x:A | no x.r'):
            with self.subTest(fixture=('one A', 'no r', 'all x:A | no x.r').index(learner)):
                distances = [pair(learner, reference)['distance'] for reference in bodies]
                result = run_request(pool_request(learner, bodies))
                self.assert_complete(result, len(bodies))
                self.assertEqual(result['distance'], min(distances))
                winner = distances.index(min(distances))
                self.assertEqual(result['operations'], pair(learner, bodies[winner])['operations'])

    def test_trace_comes_from_nearest_candidate_not_first_reference(self):
        result = run_request(pool_request('no A', ['all x:A | some x.r', 'some A']))
        self.assert_complete(result, 2)
        self.assertEqual(result['distance'], 1)
        self.assertEqual(result['operations'], pair('no A', 'some A')['operations'])
        self.assertEqual(result['operations'][0]['replacementOperator'], 'some')

    def test_correct_formulation_can_match_zero_while_oracle_is_positive(self):
        # Both formulas express absence of self-loops; Fast Rewrite retains
        # different canonical shapes. The admitted variant must count as a target.
        correct_variant = 'all x:A | x not in x.r'
        oracle = 'no iden & r'
        self.assertGreater(pair(correct_variant, oracle)['distance'], 0)
        result = run_request(pool_request(correct_variant, [oracle, correct_variant]))
        self.assert_complete(result, 2)
        self.assertEqual(result['distance'], 0)
        self.assertEqual(result['operations'], [])

    def test_equal_distance_ties_follow_private_input_order(self):
        first = run_request(pool_request('some A', ['no A', 'one A']))
        reverse = run_request(pool_request('some A', ['one A', 'no A']))
        self.assert_complete(first, 2)
        self.assert_complete(reverse, 2)
        self.assertEqual(first['distance'], reverse['distance'])
        self.assertEqual(first['operations'][0]['replacementOperator'], 'no')
        self.assertEqual(reverse['operations'][0]['replacementOperator'], 'one')

    def test_invalid_candidate_after_zero_fails_entire_pool_privately(self):
        for bodies in (['some A', 'some PRIVATE_POOL_SENTINEL'],
                       ['some PRIVATE_POOL_SENTINEL', 'some A']):
            result = run_request(pool_request('some A', bodies))
            self.assertEqual(result['status'], 'engine_error')
            self.assertNotIn('distance', result)
            self.assertNotIn('comparison', result)
            self.assertNotIn('operations', result)
            self.assertNotIn('PRIVATE_POOL_SENTINEL', json.dumps(result))

    def test_invalid_pool_shapes_and_oracle_fallback_are_rejected(self):
        base = pool_request('some A', ['some A'])
        invalid = []
        for bodies in ([], [''], [' '], [None], [False], [{}], ['some A'] * 4097, 'some A'):
            request = copy.deepcopy(base)
            request['referenceBodies'] = bodies
            invalid.append(request)
        for missing in ('referenceBodies', 'referencePrefix', 'referenceSuffix'):
            request = copy.deepcopy(base)
            request.pop(missing)
            invalid.append(request)
        request = copy.deepcopy(base)
        request['oracleSource'] = ENV + 'pred target { some A }'
        invalid.append(request)
        for index, request in enumerate(invalid):
            with self.subTest(fixture=index):
                self.assertEqual(run_request(request)['status'], 'invalid_request')

    def test_duplicate_and_zero_candidates_still_report_complete_pool(self):
        result = run_request(pool_request('some A', ['some A', 'some A', 'no A']))
        self.assert_complete(result, 3)
        self.assertEqual(result['distance'], 0)

    def test_all_private_candidates_and_numeric_literals_remain_hidden(self):
        environment = ENV + 'sig PRIVATE_POOL_A {}\nsig PRIVATE_POOL_B {}\n'
        result = run_request(pool_request('some A', ['some PRIVATE_POOL_A', 'some PRIVATE_POOL_B'], environment))
        self.assert_complete(result, 2)
        self.assertNotIn('PRIVATE_POOL_A', json.dumps(result))
        self.assertNotIn('PRIVATE_POOL_B', json.dumps(result))
        numbers = run_request(pool_request('#A = 2', ['#A = 173', '#A = 179']))
        self.assert_complete(numbers, 2)
        self.assertNotIn('173', json.dumps(numbers))
        self.assertNotIn('179', json.dumps(numbers))


def corpus_case(record, pool):
    bodies = [candidate['body'] for candidate in pool['candidates']]
    request = {'studentSource': model(record, record['starter']), 'predicate': record['predicate'],
               'referenceBodies': bodies,
               'referencePrefix': record['environmentBefore'] + record['predicateHeader'] + '{\n',
               'referenceSuffix': '\n}' + record['environmentAfter']}
    try:
        nearest = run_request(request)
        oracle = invoke(model(record, record['starter']), model(record, record['oracleBody']), record['predicate'])
        return record['id'], len(bodies), nearest, oracle, None
    except (AssertionError, OSError) as error:
        return record['id'], len(bodies), None, None, type(error).__name__


class NearestCorrectCorpusTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        records = json.loads((ROOT / 'exercises/catalogue.json').read_text())['exercises']
        pools = {pool['exerciseId']: pool for pool in
                 json.loads((ROOT / 'exercises/correct-pools.json').read_text())['pools']}
        if set(pools) != {record['id'] for record in records}:
            raise AssertionError('Every exercise must have a registered private correct pool')
        with ThreadPoolExecutor(max_workers=4) as workers:
            cls.results = list(workers.map(lambda record: corpus_case(record, pools[record['id']]), records))

    def test_all_181_starters_use_complete_correct_pool(self):
        self.assertEqual(len(self.results), 181)
        for identifier, count, nearest, oracle, error in self.results:
            with self.subTest(exercise=identifier):
                self.assertIsNone(error, 'Complete corpus-pool evaluation did not finish')
                self.assertEqual(nearest.get('status'), 'ok', 'A registered pool candidate could not be evaluated')
                self.assertEqual(oracle.get('status'), 'ok', 'Oracle baseline did not complete')
                self.assertEqual(nearest['comparison'], {'strategy': 'nearest-known-correct',
                                 'poolSize': count, 'evaluatedCandidates': count, 'complete': True})
                self.assertLessEqual(nearest['distance'], oracle['distance'], 'Pool minimum exceeded its included oracle')
                self.assertEqual(sum(op['cost'] for op in nearest['operations']), nearest['distance'])
                self.assertEqual(nearest['canonicalForm'], oracle['canonicalForm'], 'Learner canonical form depends on target selection')
                self.assertTrue(nearest['trace']['matrixReplayVerified'])

    def test_real_socialmedia_pool_improves_over_oracle(self):
        identifier, count, nearest, oracle, error = next(result for result in self.results if result[0] == 'socialMedia-inv4')
        self.assertIsNone(error)
        self.assertGreater(count, 1)
        self.assertLess(nearest['distance'], oracle['distance'])
        self.assertEqual(nearest['comparison']['evaluatedCandidates'], count)


if __name__ == '__main__':
    unittest.main(verbosity=2)
