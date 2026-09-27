"""HTTP-boundary source mapping and learner-only location disclosure."""
import copy
import json
import subprocess
import unittest
from unittest.mock import patch

import server
from luna import prompt_trace


RECORD = {'environmentBefore': '// model 🌙\r\nsig A {}\r\n',
          'predicateHeader': 'pred target ', 'environmentAfter': '\n'}


def utf16(text):
    return len(text.encode('utf-16-le')) // 2


def location(body, texts, *, record=RECORD, precision='related'):
    prefix = record['environmentBefore'] + record['predicateHeader'] + '{\n'
    ranges, cursor = [], 0
    for text in texts:
        start = body.index(text, cursor)
        cursor = start + len(text)
        ranges.append({'start': utf16(prefix + body[:start]), 'end': utf16(prefix + body[:cursor])})
    return {'status': 'located' if len(ranges) == 1 else 'ambiguous',
            'precision': precision, 'coordinateSystem': 'module', 'offsetEncoding': 'utf-16',
            'ranges': ranges}


class SourceProjectionTests(unittest.TestCase):
    def project(self, body, raw):
        operations = [{'kind': 'replace', 'cost': 1, 'sourceLocation': copy.deepcopy(raw)}]
        server.project_source_locations(operations, RECORD, body)
        return operations[0]['sourceLocation']

    def test_multiline_tabs_crlf_and_non_bmp_have_bound_utf16_coordinates(self):
        body = '// 🌟\r\n\tno A\r\n'
        result = self.project(body, location(body, ['no A']))
        self.assertEqual(result['status'], 'located')
        self.assertEqual(result['coordinateSystem'], 'body')
        self.assertEqual(result['offsetEncoding'], 'utf-16')
        span = result['ranges'][0]
        self.assertEqual(span, {'start': 8, 'end': 12, 'text': 'no A',
                                'startLine': 2, 'startColumn': 2, 'endLine': 2, 'endColumn': 6,
                                'moduleLine': 5, 'moduleColumn': 2})

    def test_boundary_recomputes_text_and_coordinates_and_strips_freeform_fields(self):
        body = 'no A'
        raw = location(body, [body], precision='exact')
        raw.update(reason='PRIVATE_REFERENCE', target='PRIVATE_REFERENCE')
        raw['ranges'][0].update(text='PRIVATE_REFERENCE', startLine=999, moduleLine=999)
        result = self.project(body, raw)
        self.assertEqual(result['precision'], 'related')
        self.assertEqual(result['ranges'][0]['text'], body)
        self.assertEqual(result['ranges'][0]['startLine'], 1)
        self.assertEqual(result['ranges'][0]['moduleLine'], 4)
        self.assertNotIn('PRIVATE_REFERENCE', json.dumps(result))
        self.assertNotIn('target', result)

    def test_duplicate_expressions_remain_distinct_ambiguous_locations(self):
        body = 'no A\nand no A'
        raw = location(body, ['no A', 'no A'])
        raw['ranges'].reverse()
        result = self.project(body, raw)
        self.assertEqual(result['status'], 'ambiguous')
        self.assertEqual([span['startLine'] for span in result['ranges']], [1, 2])
        self.assertEqual([span['start'] for span in result['ranges']], [0, 9])

    def test_selected_node_preserves_second_repeated_source_occurrence(self):
        body = '// 🌙\nno A\nand no A'
        raw = location(body, ['no A', 'no A'], precision='node')
        raw.update(status='located', ranges=raw['ranges'][1:])
        raw['ranges'][0]['text'] = 'PRIVATE_TARGET'
        result = self.project(body, raw)
        self.assertEqual(result['precision'], 'node')
        self.assertEqual(result['status'], 'located')
        self.assertEqual(len(result['ranges']), 1)
        self.assertEqual(result['ranges'][0]['start'], utf16(body[:body.rindex('no A')]))
        self.assertEqual(result['ranges'][0]['startLine'], 3)
        self.assertEqual(result['ranges'][0]['text'], 'no A')
        self.assertNotIn('PRIVATE_TARGET', json.dumps(result))

    def test_selected_source_node_cannot_claim_ambiguous_occurrences(self):
        body = 'no A and no A'
        raw = location(body, ['no A', 'no A'], precision='node')
        for status in ('ambiguous', 'located'):
            with self.subTest(status=status):
                result = self.project(body, dict(raw, status=status))
                self.assertEqual(result['status'], 'unavailable')
                self.assertEqual(result['ranges'], [])

    def test_invalid_or_partial_mappings_are_unavailable_without_leaking_worker_text(self):
        body = '// 🌟\nno A'
        base = location(body, ['no A'])
        origin = utf16(RECORD['environmentBefore'] + RECORD['predicateHeader'] + '{\n')
        bad_ranges = [[{'start': origin - 1, 'end': origin + 2}],
                      [{'start': origin, 'end': origin + utf16(body) + 1}],
                      [{'start': origin + 4, 'end': origin + 5}],  # Inside emoji surrogate pair.
                      [{'start': True, 'end': origin + 3}],
                      [{'start': origin + 3, 'end': origin + 3}],
                      [base['ranges'][0], {'start': 0, 'end': 1}],
                      base['ranges'] * 2, base['ranges'] * 17, []]
        variants = [dict(base, ranges=ranges,
                         status='located' if len(ranges) == 1 else 'ambiguous') for ranges in bad_ranges]
        variants += [dict(base, coordinateSystem='body'), dict(base, offsetEncoding='utf-8'),
                     dict(base, precision='certain'), dict(base, status='ambiguous'),
                     None, [], {'status': 'unavailable', 'reason': 'PRIVATE_REFERENCE'}]
        for raw in variants:
            with self.subTest(raw=raw):
                result = self.project(body, raw)
                self.assertEqual(result['status'], 'unavailable')
                self.assertEqual(result['ranges'], [])
                self.assertNotIn('PRIVATE_REFERENCE', json.dumps(result))

    def test_legacy_unbound_span_is_removed_and_predicate_context_stays_explicit(self):
        operations = [{'sourceSpan': {'text': 'PRIVATE_REFERENCE'}}]
        server.project_source_locations(operations, RECORD, 'no A')
        self.assertNotIn('sourceSpan', operations[0])
        self.assertEqual(operations[0]['sourceLocation']['status'], 'unavailable')
        result = self.project('no A', location('no A', ['no A'], precision='predicate'))
        self.assertEqual(result['precision'], 'predicate')
        self.assertIn('whole predicate', result['reason'])

    def test_locator_source_text_is_not_added_to_luna_prompt(self):
        trace = {'distance': 1, 'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': 1},
                 'operations': [{'kind': 'replace', 'component': 'matrix', 'cost': 1,
                                 'sourceTerm': '(no A)', 'sourceOperator': 'no',
                                 'canonicalLocation': {'ranges': [{'text': 'RAW_CANONICAL_LOCATION'}]},
                                 'sourceLocation': {'ranges': [{'text': 'RAW_SOURCE_WITH_PRIVATE_COMMENT'}]}}]}
        projected = prompt_trace(trace)
        self.assertNotIn('sourceLocation', json.dumps(projected))
        self.assertNotIn('RAW_SOURCE_WITH_PRIVATE_COMMENT', json.dumps(projected))
        self.assertNotIn('canonicalLocation', json.dumps(projected))
        self.assertNotIn('RAW_CANONICAL_LOCATION', json.dumps(projected))


class CanonicalProjectionTests(unittest.TestCase):
    def test_compaction_preserves_literal_whitespace_and_escaped_quotes(self):
        literal = '"🌙  a\t b \\"  c"'
        raw = ' \n root  normal\tform :=  target( ' + literal + '  ) \r\n'
        compact, offsets = server.compact_canonical_text(raw)
        self.assertEqual(compact, 'root normal form := target( ' + literal + ' )')
        self.assertEqual(server.compact_canonical_text(compact)[0], compact)
        self.assertEqual(offsets[utf16(raw)], utf16(compact))
        start = raw.index(literal)
        a, b = offsets[utf16(raw[:start])], offsets[utf16(raw[:start] + literal)]
        self.assertEqual(compact.encode('utf-16-le')[a * 2:b * 2].decode('utf-16-le'), literal)

    def project(self, forms, ranges, *, precision='related', status=None):
        result = {'canonicalForm': forms,
                  'operations': [{'canonicalLocation': {
                      'status': status or ('located' if len(ranges) == 1 else 'ambiguous'),
                      'precision': precision, 'coordinateSystem': 'canonical', 'offsetEncoding': 'utf-16',
                      'ranges': ranges, 'reason': 'PRIVATE_TARGET'}}]}
        server.project_canonical_locations(result)
        self.assertNotIn('PRIVATE_TARGET', json.dumps(result))
        return result

    def test_ranges_follow_compacted_text_across_forms(self):
        forms = [' root  normal form := target((NO   (A  . r))) ',
                 '\nnext  form := target((SOME   A))\n']
        parts = ['(NO   (A  . r))', '(SOME   A)']
        ranges = [{'formIndex': index, 'start': form.index(part), 'end': form.index(part) + len(part),
                   'text': 'PRIVATE_TARGET'} for index, (form, part) in enumerate(zip(forms, parts))]
        result = self.project(forms, ranges)
        locator = result['operations'][0]['canonicalLocation']
        self.assertEqual(locator['status'], 'ambiguous')
        self.assertEqual([r['text'] for r in locator['ranges']], ['(NO (A . r))', '(SOME A)'])
        for span in locator['ranges']:
            self.assertEqual(result['canonicalForm'][span['formIndex']][span['start']:span['end']], span['text'])

    def test_form_context_is_explicit_and_not_a_precise_fragment(self):
        form = '  root := (NO A)  '
        result = self.project([form], [{'formIndex': 0, 'start': 0, 'end': len(form)}], precision='form')
        locator = result['operations'][0]['canonicalLocation']
        self.assertEqual(locator['precision'], 'form')
        self.assertEqual(locator['ranges'][0]['text'], 'root := (NO A)')
        self.assertIn('a smaller matching part could not be found', locator['reason'])

    def test_selected_node_preserves_second_canonical_occurrence_after_compaction(self):
        form = '  🌙 root := (NO   A)  and\n (NO   A)  '
        start = form.rindex('(NO')
        result = self.project([form], [{'formIndex': 0, 'start': utf16(form[:start]),
                                       'end': utf16(form[:start] + '(NO   A)')}], precision='node')
        locator = result['operations'][0]['canonicalLocation']
        compact = result['canonicalForm'][0]
        self.assertEqual(locator['precision'], 'node')
        self.assertEqual(locator['status'], 'located')
        self.assertEqual(locator['ranges'], [{'formIndex': 0, 'start': utf16(compact[:compact.rindex('(NO')]),
                                             'end': utf16(compact), 'text': '(NO A)'}])

    def test_selected_canonical_node_cannot_claim_ambiguous_occurrences(self):
        ranges = [{'formIndex': 0, 'start': 0, 'end': 6}, {'formIndex': 0, 'start': 11, 'end': 17}]
        for status in ('ambiguous', 'located'):
            with self.subTest(status=status):
                result = self.project(['(NO A) and (NO A)'], ranges, precision='node', status=status)
                locator = result['operations'][0]['canonicalLocation']
                self.assertEqual(locator['status'], 'unavailable')
                self.assertEqual(locator['ranges'], [])

    def test_invalid_canonical_mapping_fails_closed(self):
        form = '("🌙")'
        valid = {'formIndex': 0, 'start': 0, 'end': utf16(form)}
        for ranges in ([dict(valid, formIndex=1)], [dict(valid, formIndex=True)],
                       [dict(valid, start=3)], [dict(valid, end=100)], [dict(valid, start=-1)],
                       [dict(valid, end=0)], [valid, valid], [valid] * 17,
                       [valid, dict(valid, formIndex=1)]):
            with self.subTest(ranges=ranges):
                result = self.project([form], ranges)
                self.assertEqual(result['operations'][0]['canonicalLocation']['status'], 'unavailable')
                self.assertEqual(result['operations'][0]['canonicalLocation']['ranges'], [])


class SourceProjectionEngineBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0))
        cls.record = cls.app.exercises['graphs-inv1']

    @classmethod
    def tearDownClass(cls):
        cls.app.server_close()

    def test_projection_and_cache_are_bound_to_each_submitted_body(self):
        for body in ('no Node // locator-first', '\n\tno Node // locator-second'):
            with self.subTest(body=body):
                answer = {'status': 'ok', 'distance': 1,
                          'operations': [{'kind': 'replace', 'cost': 1,
                                          'sourceLocation': location(body, ['no Node'], record=self.record)}],
                          'comparison': {'strategy': 'nearest-known-correct',
                                         'poolSize': len(self.app.correct_pools[self.record['id']]),
                                         'evaluatedCandidates': len(self.app.correct_pools[self.record['id']]),
                                         'complete': True}}
                with patch('server.subprocess.run', return_value=subprocess.CompletedProcess(
                        [], 0, json.dumps(answer), '')) as engine:
                    result = self.app.evaluate(self.record, body)
                    cached = self.app.evaluate(self.record, body)
                self.assertEqual(engine.call_count, 1)
                self.assertEqual(result, cached)
                span = result['operations'][0]['sourceLocation']['ranges'][0]
                self.assertEqual(span['start'], body.index('no Node'))
                self.assertEqual(span['text'], 'no Node')
                self.assertEqual(span['startLine'], 2 if body.startswith('\n') else 1)


if __name__ == '__main__':
    unittest.main()
