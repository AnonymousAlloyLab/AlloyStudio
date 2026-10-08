"""Finite repair grammar, disclosure limits and actual HTTP adapter contract."""
import copy
import json
from pathlib import Path
import shutil
import unittest

from luna import REPLACEMENT_OPERATORS, prompt_trace
import repair_grammar as grammar
import server
from runtime_dependencies import open_engine_admission, run_engine, runtime_classpath
from traffic_scheduler import EvidenceStore, ResultCache, encode


def operation(kind='replace', component='matrix', **fields):
    result = dict(kind=kind, component=component, path='normalForm[0].matrix.child[1]',
                  cost=1, aggregate=False, sourceNodeKind='formula', sourceOperator='no')
    result.update(fields)
    return result


class RepairGrammarTests(unittest.TestCase):
    def encode(self, rows):
        return grammar.encode_operations(rows, sum(row['cost'] for row in rows))

    def test_dictionary_contains_only_finite_ids_and_matches_existing_operator_policy(self):
        dictionary = grammar.dictionary()
        self.assertEqual(dictionary['version'], 1)
        self.assertEqual(dictionary['status'], 'complete')
        self.assertEqual(set(dictionary['operators'].values()), REPLACEMENT_OPERATORS)
        self.assertEqual(len(dictionary['operators']), len(REPLACEMENT_OPERATORS))
        self.assertEqual(len(dictionary['actions']), 4)
        self.assertEqual(len(dictionary['subjects']), 14)
        self.assertEqual(set(dictionary['components'].values()), {'temporal', 'quantifier', 'matrix', 'ast'})
        dictionary['actions']['r'] = 'caller mutation'
        self.assertEqual(grammar.dictionary()['actions']['r'], 'replace')

    def test_all_allowed_operator_tokens_round_trip_by_dictionary_id(self):
        dictionary = grammar.dictionary()
        for token in REPLACEMENT_OPERATORS:
            with self.subTest(operator=token):
                row = self.encode([operation(sourceOperator=token, replacementOperator=token)])[0]
                self.assertEqual(dictionary['operators'][row['sourceOperator']], token)
                self.assertEqual(dictionary['operators'][row['replacementOperator']], token)

    def test_all_repair_subject_categories_are_representable(self):
        cases = [('c', dict(sourceOperator='implies')), ('p', dict(sourceOperator='!in')),
                 ('r', dict(sourceOperator='.')), ('m', dict(sourceOperator='lone')),
                 ('a', dict(sourceOperator='%')), ('t', dict(sourceOperator='always')),
                 ('b', dict(sourceNodeKind='binding')), ('v', dict(sourceNodeKind='variable')),
                 ('n', dict(sourceNodeKind='reference')), ('k', dict(sourceNodeKind='constant')),
                 ('f', dict(sourceNodeKind='call')), ('o', dict(sourceOperator='*')),
                 ('s', dict(sourceOperator='none', sourceNodeKind='structure')),
                 ('g', dict(kind='component-edit', aggregate=True, cost=4))]
        self.assertEqual({subject for subject, _ in cases}, set(grammar.dictionary()['subjects']))
        for subject, fields in cases:
            with self.subTest(subject=subject):
                self.assertEqual(self.encode([operation(**fields)])[0]['subject'], subject)

    def test_actions_preserve_order_cost_index_and_legacy_modify_meaning(self):
        rows = [operation(kind='insert'), operation(kind='delete'), operation(kind='replace'),
                operation(kind='modify'), operation(kind='component-edit', cost=7, aggregate=True)]
        result = self.encode(rows)
        self.assertEqual([row['action'] for row in result], ['a', 'd', 'r', 'r', 'g'])
        self.assertEqual([row['operationIndex'] for row in result], list(range(5)))
        self.assertEqual([row['cost'] for row in result], [1, 1, 1, 1, 7])
        self.assertEqual([row['role'] for row in result], ['i', 'a', 'a', 'a', 'g'])
        self.assertEqual(result, self.encode(rows))

    def test_binding_and_variable_names_are_masked_in_entire_compact_output(self):
        first = operation(component='quantifier', sourceNodeKind='binding', sourceOperator='all',
                          sourceTerm='all learnerAlpha: SecretDomain', targetName='hiddenBeta',
                          replacementExpression='all hiddenBeta: HiddenDomain')
        second = copy.deepcopy(first)
        second.update(sourceTerm='all learnerGamma: DifferentDomain', targetName='hiddenDelta',
                      replacementExpression='all hiddenDelta: DifferentHiddenDomain')
        self.assertEqual(self.encode([first]), self.encode([second]))
        encoded = json.dumps(dict(grammar=grammar.dictionary(), sequence=self.encode([first])))
        for marker in ('learnerAlpha', 'SecretDomain', 'hiddenBeta', 'HiddenDomain', 'sourceTerm', 'targetName'):
            self.assertNotIn(marker, encoded)
        self.assertEqual(self.encode([first])[0]['bindingSlot'], 'b0')

    def test_binding_slots_follow_selected_structural_occurrence_and_not_term_matching(self):
        rows = [operation(sourceNodeKind='variable', sourceOperator='duplicateBinding',
                          sourceTerm='duplicateBinding', path=path)
                for path in ('normalForm[0].matrix.child[1]', 'normalForm[0].matrix.child[2]',
                             'normalForm[0].matrix.child[1]')]
        result = self.encode(rows)
        self.assertEqual([row['bindingSlot'] for row in result], ['b0', 'b1', 'b0'])
        self.assertEqual([row['path'] for row in result], [row['path'] for row in rows])
        self.assertNotIn('duplicateBinding', json.dumps(result))

    def test_learner_ranges_are_referenced_without_copying_text_or_mutating_them(self):
        rows = [operation(sourceLocation={'ranges': [{'start': 14, 'end': 19, 'text': 'LearnerOnly'}]},
                          canonicalLocation={'ranges': [{'formIndex': 0, 'start': 7, 'end': 12,
                                                         'text': 'LearnerOnly'}]})]
        original = copy.deepcopy(rows)
        result = self.encode(rows)
        self.assertEqual(rows, original)
        self.assertEqual(result[0]['operationIndex'], 0)
        self.assertNotIn('LearnerOnly', json.dumps(result))
        self.assertNotIn('ranges', json.dumps(result))

    def test_insert_anchor_does_not_claim_the_hidden_inserted_operator_or_binding(self):
        for node, token in (('binding', 'all'), ('formula', 'always'), ('variable', 'chosenName')):
            with self.subTest(node=node):
                row = self.encode([operation(kind='insert', sourceNodeKind=node, sourceOperator=token,
                                             sourceRole='insertion-anchor')])[0]
                self.assertEqual(row['subject'], 's')
                self.assertEqual(row['role'], 'i')
                self.assertNotIn('bindingSlot', row)
                self.assertNotIn('replacementOperator', row)

    def test_public_quantifier_and_temporal_inserts_keep_the_known_component_category(self):
        rows = [operation(kind='insert', component='quantifier', path='normalForm[0].quantifier[0]'),
                operation(kind='insert', component='temporal', path='temporal')]
        result = self.encode(rows)
        self.assertEqual([row['subject'] for row in result], ['b', 't'])
        self.assertEqual(result[0]['bindingSlot'], 'b0')

    def test_ambiguous_set_and_integer_operator_tokens_do_not_guess_a_family(self):
        for token in ('+', '-', '*'):
            self.assertEqual(self.encode([operation(sourceOperator=token)])[0]['subject'], 'o')

    def test_arbitrary_source_operator_name_is_omitted_and_reference_category_kept(self):
        row = self.encode([operation(sourceNodeKind='reference', sourceOperator='PrivateRelationName')])[0]
        self.assertEqual(row['subject'], 'n')
        self.assertNotIn('sourceOperator', row)
        self.assertNotIn('PrivateRelationName', json.dumps(row))

    def test_unknown_source_node_kind_never_becomes_a_new_dictionary_entry(self):
        for value in ('PRIVATE_TARGET', ['malformed'], None):
            row = self.encode([operation(sourceNodeKind=value, sourceOperator='none')])[0]
            self.assertEqual(row['subject'], 's')
            self.assertNotIn('PRIVATE_TARGET', json.dumps(row))

    def test_raw_ast_edits_use_same_dictionary_without_canonical_locations(self):
        row = self.encode([operation(component='ast', path='ast[8]', sourceNodeKind='binding',
                                    sourceOperator='binding')])[0]
        self.assertEqual((row['component'], row['subject'], row['path']), ('a', 'b', 'ast[8]'))
        self.assertEqual(row['bindingSlot'], 'b0')

    def test_zero_distance_has_complete_empty_sequence(self):
        feedback = dict(status='ok', distance=0, operations=[])
        grammar.attach(feedback)
        self.assertEqual(feedback['repairGrammar']['status'], 'complete')
        self.assertEqual(feedback['repairSequence'], [])

    def test_incorrect_costs_and_unknown_categories_reject_all_rows(self):
        variants = [dict(kind='unknown'), dict(kind=[]), dict(component='private'), dict(component={}),
                    dict(cost=0), dict(cost=True), dict(cost=1.5), dict(cost=2),
                    dict(aggregate='false'), dict(aggregate=True), dict(kind='component-edit'),
                    dict(sourceRole='private-role'), dict(kind='insert', sourceRole='affected')]
        for fields in variants:
            with self.subTest(fields=fields):
                rows = [operation(), operation(**fields)]
                feedback = dict(status='ok', distance=2, operations=rows)
                grammar.attach(feedback)
                self.assertEqual(feedback['repairGrammar']['status'], 'unavailable')
                self.assertEqual(feedback['repairSequence'], [])
                self.assertEqual(feedback['operations'], rows)

    def test_target_expressions_cannot_be_smuggled_as_replacement_operator_or_path(self):
        variants = [dict(replacementOperator='some HiddenRelation'), dict(replacementOperator=[]),
                    dict(kind='insert', replacementOperator='some'),
                    dict(kind='delete', replacementOperator='some'),
                    dict(path='normalForm[secretName].matrix'), dict(path='normalForm[0].matrix.HiddenName'),
                    dict(path='ast[7] PRIVATE_TARGET'), dict(path='normalForm[0].matrix' + '.child[1]' * 100),
                    dict(path='ast[123456789]'), dict(path=['PRIVATE_TARGET'])]
        for fields in variants:
            with self.subTest(fields=fields):
                feedback = dict(status='ok', distance=1, operations=[operation(**fields)])
                grammar.attach(feedback)
                self.assertEqual(feedback['repairGrammar']['status'], 'unavailable')
                self.assertEqual(feedback['repairSequence'], [])
                self.assertNotIn('PRIVATE_TARGET', json.dumps(feedback['repairGrammar']))

    def test_trace_bounds_and_cost_mismatch_are_explicitly_unavailable(self):
        variants = [(None, 0), ([None], 1), ([], True), ([], -1), ([], 1),
                    ([operation()] * (grammar.MAX_OPERATIONS + 1), grammar.MAX_OPERATIONS + 1),
                    ([operation()], grammar.MAX_COST + 1)]
        for rows, distance in variants:
            feedback = dict(status='ok', distance=distance, operations=rows)
            grammar.attach(feedback)
            self.assertEqual(feedback['repairGrammar']['status'], 'unavailable')
            self.assertEqual(feedback['repairSequence'], [])

    def test_non_success_responses_are_unchanged(self):
        for status in ('invalid', 'unsupported', 'timeout', 'error'):
            result = dict(status=status, diagnostics=[{'message': 'Existing diagnostic'}])
            original = copy.deepcopy(result)
            grammar.attach(result)
            self.assertEqual(result, original)


class RepairGrammarHttpBoundaryTests(unittest.TestCase):
    def project(self, raw, body='no A', metric='canonical'):
        class Stub:
            def _engine(self, kind, payload):
                return copy.deepcopy(raw)
        record = dict(environmentBefore='sig A {}\n', predicateHeader='pred inv1 ', environmentAfter='')
        return server.Portal._feedback(Stub(), record, body, metric, {'referenceBodies': ['some A']})

    def feedback(self, metric='canonical'):
        return dict(status='ok', metric=server.METRICS[metric], distance=1,
                    breakdown={'temporal': 0, 'quantifier': 0, 'matrix': 1},
                    operations=[operation(replacementOperator='some')], canonicalForm=[' (no A) '],
                    comparison=dict(strategy='nearest-known-correct', poolSize=1,
                                    evaluatedCandidates=1, complete=True))

    def test_actual_adapter_regenerates_grammar_and_keeps_distances_and_luna_trace(self):
        raw = self.feedback()
        raw.update(repairGrammar={'operators': {'x': 'PRIVATE_ORACLE'}},
                   repairSequence=[{'targetExpression': 'PRIVATE_ORACLE'}], oracleBody='PRIVATE_ORACLE')
        result = self.project(raw)
        self.assertEqual(result['distance'], raw['distance'])
        self.assertEqual(result['breakdown'], raw['breakdown'])
        self.assertEqual(result['repairGrammar']['status'], 'complete')
        self.assertEqual(len(result['repairSequence']), 1)
        row = result['repairSequence'][0]
        self.assertEqual(result['repairGrammar']['operators'][row['replacementOperator']], 'some')
        self.assertEqual(result['canonicalForm'], ['(no A)'])
        self.assertNotIn('PRIVATE_ORACLE', json.dumps(result))
        legacy = copy.deepcopy(result)
        legacy.pop('repairGrammar')
        legacy.pop('repairSequence')
        self.assertEqual(prompt_trace(result), prompt_trace(legacy))

    def test_actual_adapter_does_not_add_grammar_to_errors(self):
        result = self.project({'status': 'unsupported', 'diagnostics': [{'message': 'Try again'}]})
        self.assertNotIn('repairGrammar', result)
        self.assertNotIn('repairSequence', result)

    def test_actual_adapter_keeps_existing_structural_locator_selection(self):
        raw = self.feedback()
        prefix = 'sig A {}\npred inv1 {\n'
        raw['operations'][0].update(
            sourceLocation=dict(status='located', precision='node', coordinateSystem='module',
                                offsetEncoding='utf-16', ranges=[dict(start=len(prefix), end=len(prefix) + 4)]),
            canonicalLocation=dict(status='located', precision='node', coordinateSystem='canonical',
                                   offsetEncoding='utf-16', ranges=[dict(formIndex=0, start=2, end=6)]))
        result = self.project(raw)
        row = result['repairSequence'][0]
        selected = result['operations'][row['operationIndex']]
        self.assertEqual(selected['sourceLocation']['ranges'][0]['text'], 'no A')
        self.assertEqual(selected['canonicalLocation']['ranges'][0]['text'], 'no A')
        self.assertEqual(selected['sourceLocation']['precision'], 'node')
        self.assertNotIn('no A', json.dumps(row))


class RepairGrammarRetentionTests(unittest.TestCase):
    def feedback(self):
        result = dict(status='ok', distance=1, operations=[operation(replacementOperator='some')])
        grammar.attach(result)
        return result

    def test_result_cache_saves_complete_sequences_with_existing_byte_accounting(self):
        result = self.feedback()
        cache = ResultCache(2, 65536)
        cache['exact-payload'] = result
        saved = cache['exact-payload']
        self.assertEqual(saved['repairSequence'], result['repairSequence'])
        self.assertEqual(saved['repairGrammar'], result['repairGrammar'])
        self.assertGreaterEqual(cache.bytes, len(encode(result)))
        saved['repairSequence'][0]['cost'] = 999
        saved['repairGrammar']['operators'].clear()
        self.assertEqual(cache['exact-payload']['repairSequence'][0]['cost'], 1)
        self.assertEqual(cache['exact-payload']['repairGrammar'], grammar.dictionary())

    def test_evidence_pin_accounts_grammar_and_retains_exact_body_metric_identity(self):
        identity = ('service', 'generation', 'feedback', 'exercise', 'no A', 'canonical', 'channel')
        result = self.feedback()
        pins = EvidenceStore(maximum=2, budget=65536)
        token = pins.pin(identity, result)
        self.assertIsInstance(token, str)
        self.assertEqual(pins.bytes, len(encode(result)) + len(encode(identity)))
        self.assertEqual(pins.get(token, identity)['repairSequence'], result['repairSequence'])
        self.assertIsNone(pins.get(token, identity[:-2] + ('ast', 'channel')))
        self.assertIsNone(pins.get(token, identity[:4] + ('some A',) + identity[5:]))
        self.assertEqual(pins.pin(identity, result), token)
        too_small = EvidenceStore(maximum=2, budget=len(encode(result)) - 1)
        self.assertIsNone(too_small.pin(identity, result))
        self.assertEqual(too_small.bytes, 0)


class RepairGrammarRealEngineTests(unittest.TestCase):
    def test_current_jvm_traces_and_http_projection_encode_both_metrics(self):
        root = Path(__file__).resolve().parents[1]
        java = shutil.which('java')
        if java is None or not (root / 'build/engine/classes/live/LiveFeedback.class').is_file():
            self.skipTest('Build the JVM engine to run the actual trace adapter check')
        open_engine_admission(root)
        environment = 'sig A { r: set A }\n'
        record = dict(environmentBefore=environment, predicateHeader='pred inv1 ', environmentAfter='')
        command = [java, '-Xmx256m', '-XX:ActiveProcessorCount=1', '-cp', runtime_classpath(root),
                   'live.LiveFeedback']

        class ActualEngine:
            def _engine(self, kind, payload):
                completed = run_engine(command, root=root, lane=kind, input=json.dumps(payload),
                                       text=True, encoding='utf-8', capture_output=True,
                                       cwd=root, timeout=15, check=False)
                if completed.returncode or completed.stderr:
                    raise AssertionError('The actual engine could not evaluate a grammar fixture')
                return json.loads(completed.stdout)

        cases = [('canonical', 'no A', 'some A'),
                 ('canonical', 'all x: A | no x.r', 'some x: A | no x.r'),
                 ('ast', 'all x: A | no x.r', 'some x: A | no x.r'),
                 ('ast', 'no A', 'no A')]
        for metric, learner, correct in cases:
            with self.subTest(metric=metric, fixture=cases.index((metric, learner, correct))):
                payload = dict(metric=metric, predicate='inv1', studentSource=server.model(record, learner),
                               referencePrefix=environment + 'pred inv1 {\n', referenceSuffix='\n}\n',
                               referenceBodies=[correct])
                result = server.Portal._feedback(ActualEngine(), record, learner, metric, payload)
                self.assertEqual(result['status'], 'ok')
                self.assertEqual(result['repairGrammar']['status'], 'complete')
                self.assertEqual(len(result['repairSequence']), len(result['operations']))
                self.assertEqual(sum(row['cost'] for row in result['repairSequence']), result['distance'])
                self.assertEqual([row['operationIndex'] for row in result['repairSequence']],
                                 list(range(len(result['operations']))))
                for row in result['repairSequence']:
                    self.assertNotIn('sourceTerm', row)
                    self.assertNotIn('sourceLocation', row)
                    self.assertNotIn('canonicalLocation', row)


if __name__ == '__main__':
    unittest.main()
