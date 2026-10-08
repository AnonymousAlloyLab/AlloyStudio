"""Explicit pre-admission gate witnesses for the private review cache.

In particular, a separate bounded solver disagreement must stay below 1.000
and must never enter the cache, regardless of its agreeing sampled instances.
"""
from copy import deepcopy
import unittest
from unittest.mock import patch

import candidate_store as cache
import exercise_store as store
from server import project_behavior
import test_admin_candidates as fixtures


def instance(atoms, edges):
    return dict(traceLength=1, loopState=-1, truncated=False,
                stringsAnonymized=False, states=[dict(index=0,
                    signatures=[dict(label='this/Node', atoms=atoms)],
                    relations=[dict(label='this/Node<:adj', arity=2,
                                    tuples=edges)])])


class CandidateAdmissionGateTests(unittest.TestCase):
    setUp = fixtures.AdminCandidatesTests.setUp
    tearDown = fixtures.AdminCandidatesTests.tearDown
    admit = fixtures.AdminCandidatesTests.admit
    detail = fixtures.AdminCandidatesTests.detail

    def test_rare_concrete_under_or_overcoverage_is_below_one_and_never_enters_cache(self):
        # The fixture oracle is `no iden & adj`: an empty graph satisfies it,
        # whereas a one-node self loop does not. These are concrete witnesses
        # against the respective otherwise-plausible student expressions.
        cases = (
            ('undercoverage', 'no iden & adj and some Node', instance([], [])),
            ('overcoverage', 'no iden & adj or one Node',
             instance(['Node$0'], [['Node$0', 'Node$0']])),
        )
        for name, body, witness in cases:
            with self.subTest(category=name):
                raw = fixtures.perfect()
                raw['sampling'].update(positiveTested=100, positiveAccepted=100,
                    negativeTested=100, negativeRejected=100,
                    semanticCounterexamples=1)
                categories = {item['id']: item for item in raw['categories']}
                categories['both']['instances'] = [instance(['Node$0'], [])]
                categories['neither']['instances'] = [
                    instance(['Node$0', 'Node$1'], [['Node$0', 'Node$0']])]
                categories[name].update(status='sat', instances=[witness])
                # 10000 / 10001 formerly rounded to 1.000. The projection now
                # refuses that old worker claim and preserves the witnesses
                # at 0.999; admission independently rejects both forms.
                with self.assertRaises(ValueError):
                    project_behavior(raw)
                self.assertIsNone(self.admit(body=body, behavior=raw))
                raw['score'] = 0.999
                projected = project_behavior(raw)
                self.assertEqual(projected['score'], 0.999)
                self.assertEqual(projected['sampling']['semanticCounterexamples'], 1)
                self.assertIsNone(self.admit(body=body, behavior=projected))
        self.assertEqual(cache.list_candidates(self.root, self.snapshot)['total'], 0)

    def test_unknown_incomplete_or_malformed_disagreement_evidence_never_enters_cache(self):
        mutations = (
            ('undercoverage unknown', lambda x: x['categories'][1].update(status='unknown')),
            ('overcoverage unknown', lambda x: x['categories'][2].update(status='unknown')),
            ('undercoverage unfinished', lambda x: x['categories'][1].update(enumerationComplete=False)),
            ('overcoverage unfinished', lambda x: x['categories'][2].update(enumerationComplete=False)),
            ('undercoverage witness despite unsat', lambda x: x['categories'][1].update(instances=[instance([], [])])),
            ('missing category', lambda x: x['categories'].pop(2)),
            ('duplicate category', lambda x: x['categories'].__setitem__(2, deepcopy(x['categories'][1]))),
            ('wrong polarity', lambda x: x['categories'][2].update(student=False)),
            ('missing score', lambda x: x.pop('score')),
            ('string score', lambda x: x.update(score='1.000')),
            ('facts absent', lambda x: x['scope'].pop('moduleFacts')),
        )
        for description, mutate in mutations:
            with self.subTest(evidence=description):
                evidence = fixtures.perfect()
                mutate(evidence)
                self.assertIsNone(self.admit(behavior=evidence))
        self.assertEqual(cache.list_candidates(self.root, self.snapshot)['total'], 0)

    def test_complete_perfect_evidence_enters_pending_cache_without_review_or_pool_addition(self):
        with (patch('candidate_review.review') as provider,
              patch('exercise_store.prepare_approval') as approval):
            candidate = self.admit()
            self.assertIsNotNone(candidate)
            detail = self.detail(candidate)
        provider.assert_not_called()
        approval.assert_not_called()
        self.assertEqual(detail['state'], 'pending')
        self.assertIsNone(detail['review'])
        self.assertEqual(detail['behavioralEvidence']['score'], 1.0)
        for category in ('undercoverage', 'overcoverage'):
            self.assertEqual(detail['behavioralEvidence'][category], 'unsat')
        self.assertEqual(store.load_store(self.root).correct_pools,
                         self.snapshot.correct_pools)


if __name__ == '__main__':
    unittest.main()
