"""Production-boundary checks for reserved full rewards and fresh observations.

The integer calculations exercise the production projector, not an alternate
score implementation used by the application. JVM LFU transition tests live in
the worker's harness; no provider calls are made by these tests.
"""
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import threading
import time
import unittest
from unittest.mock import patch

import server
from test_behavior_portal import valid_behavior


def agreement():
    result = valid_behavior()
    result['score'] = 1.0
    result['sampling'].update(positiveTested=100, positiveAccepted=100,
                              negativeTested=100, negativeRejected=100)
    for item in result['categories']:
        if item['id'] in ('undercoverage', 'overcoverage'):
            item.update(status='unsat', enumerationComplete=True, instances=[])
    return result


def rare_mismatch(direction, *, score=0.999, complete=True):
    result = agreement()
    result['score'] = score
    result['sampling']['semanticCounterexamples'] = 1
    category = next(item for item in result['categories'] if item['id'] == direction)
    witness = valid_behavior()['categories'][0]['instances'][0]
    category.update(status='sat', enumerationComplete=complete,
                    instances=[deepcopy(witness) for _ in range(1 if complete else 3)])
    return result


class RewardProjectionContractTests(unittest.TestCase):
    def test_constructed_old_rounding_counterexample_is_rejected_in_both_directions(self):
        # 10000/10001 ordinarily rounds to 1.000 even though a solver found
        # a disagreement. The new boundary refuses this former worker claim.
        for direction in ('undercoverage', 'overcoverage'):
            with self.subTest(direction=direction):
                with self.assertRaises(ValueError):
                    server.project_behavior(rare_mismatch(direction, score=1.0))

    def test_rare_mismatch_is_preserved_and_full_reward_is_reserved(self):
        for direction in ('undercoverage', 'overcoverage'):
            with self.subTest(direction=direction):
                result = server.project_behavior(rare_mismatch(direction))
                self.assertEqual(result['score'], 0.999)
                category = next(x for x in result['categories'] if x['id'] == direction)
                self.assertEqual(category['status'], 'sat')
                self.assertEqual(len(category['instances']), 1)

    def test_two_rare_directions_still_cannot_round_to_full_reward(self):
        result = rare_mismatch('undercoverage')
        other = rare_mismatch('overcoverage')['categories'][2]
        result['categories'][2] = other
        result['sampling']['semanticCounterexamples'] = 2
        self.assertEqual(server.project_behavior(result)['score'], 0.999)
        result['score'] = 1.0
        with self.assertRaises(ValueError):
            server.project_behavior(result)

    def test_full_reward_requires_complete_unsat_disagreement_checks(self):
        self.assertEqual(server.project_behavior(agreement())['score'], 1.0)
        for direction in (1, 2):
            result = agreement()
            result['categories'][direction]['enumerationComplete'] = False
            with self.subTest(direction=direction), self.assertRaises(ValueError):
                server.project_behavior(result)

    def test_incomplete_sat_enumeration_cannot_award_full_reward(self):
        result = rare_mismatch('undercoverage', complete=False)
        self.assertEqual(server.project_behavior(result)['score'], 0.999)
        result['score'] = 1.0
        with self.assertRaises(ValueError):
            server.project_behavior(result)

    def test_integer_half_up_rounding_preserves_existing_nonperfect_reward(self):
        result = valid_behavior()
        result['sampling'].update(positiveAccepted=1, negativeRejected=1)
        result['score'] = 0.063
        self.assertEqual(server.project_behavior(result)['score'], 0.063)
        result['score'] = 0.062
        with self.assertRaises(ValueError):
            server.project_behavior(result)

    def test_dense_small_counts_agree_with_integer_contract_and_counterexample_gate(self):
        witness = valid_behavior()['categories'][0]['instances'][0]
        # A dense finite matrix checks both exact rounding and the category
        # constraints without relying on floating-point tie classification.
        for positive in range(1, 7):
            for negative in range(1, 7):
                for accepted in range(positive + 1):
                    for rejected in range(negative + 1):
                        result = agreement()
                        result['sampling'].update(positiveTested=positive,
                            positiveAccepted=accepted, negativeTested=negative,
                            negativeRejected=rejected)
                        statuses = (accepted > 0, accepted < positive,
                                    rejected < negative, rejected > 0)
                        for category, sat in zip(result['categories'], statuses):
                            category.update(status='sat' if sat else 'unsat',
                                enumerationComplete=True,
                                instances=[deepcopy(witness)] if sat else [])
                        denominator = positive * negative
                        rounded = (2000 * accepted * rejected + denominator) // (2 * denominator)
                        expected = rounded if accepted == positive and rejected == negative else min(999, rounded)
                        result['score'] = expected / 1000
                        checked = server.project_behavior(result)
                        self.assertEqual(checked['score'], expected / 1000)
                        if statuses[1] or statuses[2]:
                            self.assertLess(checked['score'], 1)

    def test_missing_oracle_polarity_keeps_score_unavailable(self):
        result = valid_behavior()
        result.update(score=None, scoreStatus='unavailable', scoreReason='ORACLE_POSITIVE_UNSAT')
        result['sampling'].update(positiveTested=0, positiveAccepted=0)
        for category in result['categories'][:2]:
            category.update(status='unsat', enumerationComplete=True, instances=[])
        self.assertIsNone(server.project_behavior(result)['score'])

    def test_worker_pool_metadata_cannot_cross_public_reward_boundary(self):
        result = agreement()
        result.update(poolEpoch=987, pool={'privateInstance': 'PRIVATE_POOL_CANARY'})
        checked = server.project_behavior(result)
        self.assertNotIn('poolEpoch', checked)
        self.assertNotIn('pool', checked)


class FreshObservationContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0), engine_mode='oneshot', workers=1)
        cls.record = cls.app.exercises['graphs-inv5']

    @classmethod
    def tearDownClass(cls):
        cls.app.server_close()

    def setUp(self):
        # Synthetic reward envelopes are scheduler fixtures, never evidence
        # for the actual administrator candidate store in this checkout.
        capture = patch.object(self.app, '_capture_candidate')
        capture.start()
        self.addCleanup(capture.stop)
        with self.app.cache_lock:
            self.app.cache.clear()
            self.app.behavior_cache.clear()

    def test_identical_sequential_requests_observe_updates_instead_of_reusing_score(self):
        first = server.project_behavior(valid_behavior())
        changed = valid_behavior()
        changed['sampling']['positiveAccepted'] = 4
        changed['score'] = 0.5
        second = server.project_behavior(changed)
        with patch.object(self.app, '_behavior', side_effect=[first, second]) as worker:
            before = self.app.evaluate_behavior(self.record, 'some Node')
            after = self.app.evaluate_behavior(self.record, 'some Node')
        self.assertEqual(worker.call_count, 2)
        self.assertEqual((before['score'], after['score']), (0.25, 0.5))
        self.assertEqual(len(self.app.behavior_cache), 1)
        key = self.app._key('behavior', self.app.behavior_payload(self.record, 'some Node'),
                            self.app.generation)
        with self.app.cache_lock:
            self.assertEqual(self.app.behavior_cache.get(key)['score'], 0.5)

    def test_retained_evidence_is_private_immutable_and_not_a_score_cache(self):
        with patch.object(self.app, '_behavior', return_value=server.project_behavior(valid_behavior())) as worker:
            result = self.app.evaluate_behavior(self.record, 'some Node')
            result['categories'][0]['instances'].clear()
            key = self.app._key('behavior', self.app.behavior_payload(self.record, 'some Node'),
                                self.app.generation)
            with self.app.cache_lock:
                snapshot = self.app.behavior_cache.get(key)
                self.assertEqual(len(snapshot['categories'][0]['instances']), 1)
                snapshot['categories'][0]['instances'].clear()
                self.assertEqual(len(self.app.behavior_cache.get(key)['categories'][0]['instances']), 1)
            self.app.evaluate_behavior(self.record, 'some Node')
        self.assertEqual(worker.call_count, 2)

    def test_inflight_duplicates_share_one_observation_but_later_request_is_fresh(self):
        started, release = threading.Event(), threading.Event()
        def observe(payload):
            started.set()
            if not release.wait(5):
                raise RuntimeError('Test observation did not release')
            return server.project_behavior(valid_behavior())
        with patch.object(self.app, '_behavior', side_effect=observe) as worker:
            with ThreadPoolExecutor(8) as followers:
                requests = [followers.submit(self.app.evaluate_behavior, self.record, 'some Node')
                            for _ in range(8)]
                self.assertTrue(started.wait(2))
                deadline = time.monotonic() + 3
                try:
                    while self.app.scheduler.stats()['subscribers'] != 8:
                        self.assertLess(time.monotonic(), deadline)
                        time.sleep(0.005)
                finally:
                    release.set()
                answers = [future.result(5) for future in requests]
            self.assertEqual(worker.call_count, 1)
            answers[0]['sampling']['positiveAccepted'] = 0
            self.assertEqual(answers[1]['sampling']['positiveAccepted'], 2)
            self.app.evaluate_behavior(self.record, 'some Node')
            self.assertEqual(worker.call_count, 2)

    def test_failed_new_observation_does_not_fall_back_to_old_full_reward(self):
        with patch.object(self.app, '_behavior', return_value=server.project_behavior(agreement())):
            self.assertEqual(self.app.evaluate_behavior(self.record, 'some Node')['score'], 1.0)
        with patch.object(self.app, '_behavior', return_value={'status': 'timeout', 'message': 'Try again.'}) as worker:
            result = self.app.evaluate_behavior(self.record, 'some Node')
        self.assertEqual(worker.call_count, 1)
        self.assertEqual(result['status'], 'timeout')
        self.assertNotIn('score', result)

    def test_structural_feedback_cache_policy_is_unchanged(self):
        key = ('reward-contract-structural-cache',)
        with patch.object(self.app, '_feedback', return_value={'status': 'ok', 'distance': 0}) as worker:
            compute = lambda: self.app._feedback(self.record, 'some Node', 'canonical', None)
            self.app._schedule('feedback', key, compute, None, 0)
            self.app._schedule('feedback', key, compute, None, 0)
        self.assertEqual(worker.call_count, 1)


if __name__ == '__main__':
    unittest.main()
