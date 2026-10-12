"""Constructed authority breaches; fixtures do not claim solver correctness."""
from copy import deepcopy
from types import SimpleNamespace
import unittest

from exercise_store import content_version
from practice_dependencies import GENERATED_STARTER
from practice_progress import CompletionAuthority, ProgressError, METRICS
from test_admin_candidates import perfect


def practice(identifier, direct=(), ancestors=()):
    before = 'sig Node { adj: set Node }\n'
    slots = []
    for name in ancestors:
        header = 'pred ' + name + ' '
        before += header + '{\n'
        start = len(before.encode('utf-8'))
        before += GENERATED_STARTER
        end = len(before.encode('utf-8'))
        before += '\n}\n'
        slots.append(dict(side='before', startByte=start, endByte=end, header=header,
                          predicate=name, exerciseId=name))
    record = dict(id=identifier, title=identifier, group='toy', predicate=identifier,
        description='Reject self loops.', environmentBefore=before, environmentAfter='',
        predicateHeader='pred ' + identifier + ' ', starter=GENERATED_STARTER,
        oracleBody='no iden & adj', source=dict(path='synthetic', sha256='0'*64))
    if ancestors:
        record['practicePlan'] = dict(version=1, direct=list(direct), dependencies=list(ancestors),
            slots=slots, blockedPredicates=[], validationEnvironmentBefore=before.replace(
                GENERATED_STARTER, 'no iden & adj'), validationEnvironmentAfter='')
    return record


def structural(metric='canonical'):
    return dict(status='ok', metric=METRICS[metric], distance=0,
                comparison=dict(complete=True))


class CompletionAuthorityTests(unittest.TestCase):
    def setUp(self):
        self.now = 100
        self.authority = CompletionAuthority(clock=lambda: self.now)
        self.records = {'A': practice('A'), 'B': practice('B', ['A'], ['A']),
                        'C': practice('C', ['B'], ['A', 'B'])}
        self.snapshot = SimpleNamespace(exercises=self.records)

    def mint(self, name, tokens=(), body='no iden & adj', metric='canonical',
             feedback=None, behavior=None):
        record = self.records[name]
        context = self.authority.resolve(record, list(tokens), self.snapshot)
        return self.authority.mint(record, body, metric, structural(metric) if feedback is None else feedback,
                                   perfect() if behavior is None else behavior, context, self.snapshot)

    def test_chain_requires_verified_predecessors_in_order(self):
        for name in ('B', 'C'):
            with self.assertRaises(ProgressError):
                self.authority.resolve(self.records[name], [], self.snapshot)
        a = self.mint('A'); b = self.mint('B', [a]); c = self.mint('C', [b])
        result = self.authority.inspect([a, b, c], self.snapshot)
        self.assertEqual(len(result['completions']), 3)
        context = self.authority.resolve(self.records['C'], [b], self.snapshot)
        self.assertEqual(dict(context.implementations), {'A':'no iden & adj', 'B':'no iden & adj'})
        self.assertEqual(context.direct_tokens, (b,))

    def test_forged_missing_foreign_duplicate_or_excess_tokens_never_authorize(self):
        a = self.mint('A')
        for tokens in (['x'*43], [], [a, a], [a, 'x'*43], [None], 'not a list', [a]*100):
            with self.subTest(tokens_type=type(tokens).__name__), self.assertRaises(ProgressError):
                self.authority.resolve(self.records['B'], tokens, self.snapshot)
        b = self.mint('B', [a])
        with self.assertRaises(ProgressError):
            self.authority.resolve(self.records['B'], [b], self.snapshot)

    def test_nonzero_wrong_metric_incomplete_or_false_structural_evidence_cannot_mint(self):
        mutations = [dict(distance=1), dict(distance=False), dict(distance=float('nan')),
                     dict(status='error'), dict(metric=METRICS['ast']), dict(comparison={'complete':False})]
        for change in mutations:
            self.assertIsNone(self.mint('A', feedback=dict(structural(), **change)))
        self.assertEqual(self.authority.stats()['records'], 0)

    def test_counterexample_or_missing_agreeing_samples_never_mints(self):
        changes = [lambda x:x.update(score=0.999), lambda x:x['sampling'].update(semanticCounterexamples=1),
            lambda x:x['categories'][1].update(status='sat', instances=[{}]),
            lambda x:x['categories'][2].update(enumerationComplete=False),
            lambda x:x['sampling'].update(positiveTested=0, positiveAccepted=0),
            lambda x:x['categories'][3].update(instances=[]), lambda x:x['scope'].update(moduleFacts=False)]
        for change in changes:
            evidence = perfect(); change(evidence)
            self.assertIsNone(self.mint('A', behavior=evidence))
        self.assertEqual(self.authority.stats()['records'], 0)

    def test_body_and_context_are_immutable_and_inspection_contains_no_code(self):
        a = self.mint('A'); b = self.mint('B', [a])
        inspected = self.authority.inspect([b], self.snapshot)['completions'][0]
        self.assertEqual(set(inspected), {'exerciseId','contentVersion','completionToken',
            'dependencyContextVersion','bodySha256','prerequisiteTokens'})
        self.assertEqual(inspected['prerequisiteTokens'], [a])
        with self.assertRaises(TypeError):
            self.authority.resolve(self.records['B'], [a], self.snapshot).implementations['A'] = 'no Node'
        anew = self.mint('A', body='no n: Node | n in n.adj')
        self.assertNotEqual(self.authority.resolve(self.records['B'], [a], self.snapshot).version,
                            self.authority.resolve(self.records['B'], [anew], self.snapshot).version)

    def test_same_body_context_recheck_across_metrics_reuses_token_without_renewing_expiry(self):
        a = self.mint('A'); self.now += 10
        self.assertEqual(a, self.mint('A', metric='ast'))
        self.now = 100 + 86400
        self.assertEqual(self.authority.inspect([a], self.snapshot)['expiredTokens'], [a])

    def test_changed_public_or_private_versions_invalidate_ancestors_and_descendants(self):
        for field in ('description', 'oracleBody'):
            a = self.mint('A'); b = self.mint('B', [a])
            original = self.records['A'][field]
            self.records['A'][field] = 'Changed'
            self.assertEqual(self.authority.inspect([a,b], self.snapshot)['expiredTokens'], [a,b])
            with self.assertRaises(ProgressError):
                self.authority.resolve(self.records['C'], [b], self.snapshot)
            self.records['A'][field] = original

    def test_expiry_restart_or_missing_predecessor_locks_downstream(self):
        a = self.mint('A'); self.now += 20; b = self.mint('B', [a])
        self.now = 100 + 86400
        self.assertEqual(self.authority.inspect([b], self.snapshot)['expiredTokens'], [b])
        other = CompletionAuthority()
        with self.assertRaises(ProgressError):
            other.resolve(self.records['B'], [a], self.snapshot)
        self.now = 100; a = self.mint('A'); b = self.mint('B', [a])
        del self.records['A']
        self.assertEqual(self.authority.inspect([b], self.snapshot)['expiredTokens'], [b])

    def test_mixed_verified_parent_chains_are_rejected(self):
        self.records['D'] = practice('D', ['A','B'], ['A','B'])
        a = self.mint('A'); b = self.mint('B', [a])
        anew = self.mint('A', body='no n: Node | n in n.adj')
        with self.assertRaises(ProgressError):
            self.authority.resolve(self.records['D'], [anew,b], self.snapshot)
        self.assertEqual(set(self.authority.resolve(self.records['D'], [b,a], self.snapshot).implementations), {'A','B'})

    def test_bounded_lru_eviction_preserves_new_receipt_ancestors(self):
        self.authority = CompletionAuthority(maximum=2, clock=lambda:self.now)
        a = self.mint('A'); b = self.mint('B', [a])
        self.assertIsNone(self.mint('C', [b]))  # cannot evict the two required ancestors
        self.assertEqual(len(self.authority.inspect([a,b], self.snapshot)['completions']), 2)
        self.records['Z'] = practice('Z'); z = self.mint('Z')
        self.assertLessEqual(self.authority.stats()['records'], 2)
        self.assertEqual(self.authority.inspect([a,b,z], self.snapshot)['expiredTokens'], [a,b])

    def test_byte_budget_oversized_body_or_bad_nonce_fails_without_authority(self):
        self.authority = CompletionAuthority(budget=128)
        self.assertIsNone(self.mint('A'))
        self.authority = CompletionAuthority()
        self.assertIsNone(self.mint('A', body='x'*8193))
        self.authority = CompletionAuthority(token_factory=lambda:'bad')
        with self.assertRaises(ProgressError): self.mint('A')
        self.assertEqual(self.authority.stats()['records'], 0)

    def test_progress_queries_are_bounded_and_bad_metadata_is_not_authority(self):
        for values in (['x'*43]*201, ['x'*43]*2, ['x'], None):
            with self.assertRaises(ProgressError): self.authority.inspect(values, self.snapshot)
        a = self.mint('A')
        self.assertEqual(self.authority.inspect([a], self.snapshot)['completions'][0]['contentVersion'],
                         content_version(self.records['A']))
        malformed = deepcopy(self.records['B'])
        malformed['practicePlan']['slots'][0]['startByte'] += 1
        with self.assertRaises(ValueError): self.authority.resolve(malformed, [a], self.snapshot)


if __name__ == '__main__':
    unittest.main()
