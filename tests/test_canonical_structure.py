"""Real prepared-graph witnesses for canonical metric-path occurrence locations."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

HARNESS = r'''package live;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.core.*;
import org.json.*;
import java.lang.reflect.Method;
import java.util.List;
public final class CanonicalStructureHarness {
  private static Method render, ordered, binding;
  private static JSONArray operations;
  private static void walk(EGraphNode node, String path) throws Exception {
    if (node == null) return;
    operations.put(new JSONObject().put("kind", "replace").put("component", "matrix")
      .put("path", path).put("sourceTerm", "IGNORED_TEXT_MATCH")
      .put("expected", (String) render.invoke(null, node)).put("node", node.getOpcode().name())
      .put("arity", node.getChildren().size()));
    for (int i=0;i<node.getChildren().size();i++) walk(node.getChildren().get(i), path+".child["+i+"]");
  }
  public static void main(String[] args) throws Exception {
    var wire = System.out;
    System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
    System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
    JSONObject request = new JSONObject(new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    String predicate = request.optString("predicate", "target");
    Canonical.Prepared learner = LiveFeedback.prepare(request.getString("source"), predicate);
    JSONArray forms = new JSONArray(Canonical.irTemporalFol(learner));
    render = CanonicalDistance.class.getDeclaredMethod("eGraphFormula", EGraphNode.class); render.setAccessible(true);
    ordered = CanonicalDistance.class.getDeclaredMethod("canonicalQuantifierOrder", List.class); ordered.setAccessible(true);
    binding = CanonicalDistance.class.getDeclaredMethod("quantifierFormula", QuantiVar.class); binding.setAccessible(true);
    operations = new JSONArray();
    List<NormalForm> normal = CanonicalLocator.normalizedForms(learner);
    for (int phase=0;phase<normal.size();phase++) {
      NormalForm form = normal.get(phase);
      walk(form.getMatrixEGraph(), "normalForm["+phase+"].matrix");
      List<?> bindings = (List<?>) ordered.invoke(null, form.getMatrixQuantiVars());
      for (int i=0;i<bindings.size();i++) operations.put(new JSONObject().put("kind","modify")
        .put("component","quantifier").put("path","normalForm["+phase+"].quantifier["+i+"]")
        .put("expected", (String) binding.invoke(null, bindings.get(i))).put("sourceTerm","IGNORED_BINDING_TEXT"));
    }
    if (request.has("operations")) operations = request.getJSONArray("operations");
    if (request.has("forms")) forms = request.getJSONArray("forms");
    CanonicalLocator.attach(learner, forms, operations);
    JSONObject result = new JSONObject().put("forms", forms).put("operations", operations);
    JSONArray localBindings = new JSONArray();
    for (int phase=0;phase<normal.size();phase++) {
      for (QuantiVar variable : normal.get(phase).getLocalQuantiVars()) {
        localBindings.put(new JSONObject().put("formIndex", phase)
          .put("name", variable.getName()).put("originalName", variable.getOriginalName())
          .put("quantifier", variable.getQuantifier().name())
          .put("bindingPath", variable.getBindingPath()));
      }
    }
    result.put("localBindings", localBindings);
    // Test-only explicit correspondence: never used by production matching.
    if (request.has("diagnosticMapping")) {
      var reference = LiveFeedback.prepare(request.getString("reference"), predicate);
      var right = CanonicalLocator.normalizedForms(reference);
      if (normal.size() != 1 || right.size() != 1) throw new IllegalArgumentException();
      java.util.Map<String, String> mapping = new java.util.HashMap<>();
      JSONObject supplied = request.getJSONObject("diagnosticMapping");
      for (String key : supplied.keySet()) mapping.put(key, supplied.getString(key));
      Method distance = CanonicalDistance.class.getDeclaredMethod("eGraphDistance",
        EGraphNode.class, EGraphNode.class, java.util.Map.class);
      distance.setAccessible(true);
      result.put("explicitMappingMatrixCost", distance.invoke(null,
        normal.get(0).getMatrixEGraph(), right.get(0).getMatrixEGraph(), mapping));
    }
    if (request.has("reference")) result.put("feedback", LiveFeedback.evaluate(new JSONObject()
      .put("studentSource", request.getString("source")).put("oracleSource", request.getString("reference"))
      .put("predicate", predicate).put("metric", "canonical")));
    wire.println(result);
  }
}
'''


def utf16_slice(value, start, end):
    return value.encode('utf-16-le')[start * 2:end * 2].decode('utf-16-le')


class CanonicalStructureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        scratch = ROOT / 'build/canonical-structure'
        scratch.mkdir(parents=True, exist_ok=True)
        cls.directory = tempfile.TemporaryDirectory(prefix='canonical-structure-', dir=scratch)
        cls.addClassCleanup(cls.directory.cleanup)
        source = Path(cls.directory.name) / 'CanonicalStructureHarness.java'
        source.write_text(HARNESS, encoding='utf-8')
        classpath = os.pathsep.join((str(ROOT / 'build/engine/classes'), str(ROOT / 'vendor/acgn/lib/*')))
        compiled = subprocess.run(['javac', '-J-Xmx256m', '-J-XX:ActiveProcessorCount=2',
                                   '-encoding', 'UTF-8', '--release', '17', '-cp', classpath,
                                   '-d', cls.directory.name, str(source)], capture_output=True, timeout=25)
        if compiled.returncode:
            raise AssertionError('Canonical structure harness did not compile')
        cls.command = ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2',
                       '-Djava.io.tmpdir=' + cls.directory.name, '-cp',
                       cls.directory.name + os.pathsep + classpath, 'live.CanonicalStructureHarness']

    def locate(self, body, environment='sig A {r, s: set A}\n', **extra):
        payload = {'source': environment + 'pred target {\n' + body + '\n}\n', **extra}
        try:
            run = subprocess.run(self.command, input=json.dumps(payload), text=True,
                                 capture_output=True, timeout=15, check=False)
        except subprocess.TimeoutExpired:
            raise AssertionError('Canonical structure witness timed out') from None
        self.assertEqual(run.returncode, 0, 'Canonical structure witness failed')
        self.assertEqual(run.stderr, '', 'Canonical structure witness emitted diagnostics')
        return json.loads(run.stdout)

    def span(self, result, operation):
        location = operation['canonicalLocation']
        self.assertEqual(location['status'], 'located')
        self.assertEqual(location['precision'], 'node')
        self.assertEqual(location['coordinateSystem'], 'canonical')
        self.assertEqual(location['offsetEncoding'], 'utf-16')
        self.assertEqual(len(location['ranges']), 1)
        span = location['ranges'][0]
        self.assertGreater(span['end'], span['start'])
        if 'expected' in operation:
            self.assertEqual(utf16_slice(result['forms'][span['formIndex']], span['start'], span['end']),
                             operation['expected'])
        return span

    def test_identical_relation_occurrences_have_distinct_exact_structural_locations(self):
        result = self.locate('some (A.r) and no (A.s)')
        repeated = [op for op in result['operations'] if op['component'] == 'matrix' and op['expected'] == 'A']
        self.assertEqual(len(repeated), 2)
        ranges = [self.span(result, op) for op in repeated]
        self.assertNotEqual(ranges[0]['start'], ranges[1]['start'])
        by_path = {op['path']: op for op in result['operations']}
        for operation, span in zip(repeated, ranges):
            parent = by_path[operation['path'].rsplit('.child[', 1)[0]]
            parent_span = self.span(result, parent)
            self.assertGreaterEqual(span['start'], parent_span['start'])
            self.assertLessEqual(span['end'], parent_span['end'])
            self.assertIn(parent['expected'], ('(A . r)', '(A . s)'))
            # The metric's child[0] leaf is immediately inside this exact join,
            # not the other equal A elsewhere in the canonical form.
            self.assertEqual(span['start'], parent_span['start'] + 1)

    def test_all_rendered_metric_nodes_match_authority_and_parent_occurrences(self):
        examples = ['some (A.r) and no (A.s)', 'r = ~r', 'some (r + s) and no (r & s)',
                    'r in ^r', 'some (A->A)', '#A > 1', 'some A implies no r',
                    'some ((some A) => r else s)', 'always (some r until no s)',
                    'all x:A | some x.r', 'no r or some s']
        for body in examples:
            with self.subTest(fixture=examples.index(body)):
                result = self.locate(body)
                paths = {}
                for operation in result['operations']:
                    paths[operation['path']] = self.span(result, operation)
                for path, span in paths.items():
                    if '.child[' in path:
                        parent = paths[path.rsplit('.child[', 1)[0]]
                        self.assertGreaterEqual(span['start'], parent['start'])
                        self.assertLessEqual(span['end'], parent['end'])
                        self.assertEqual(span['formIndex'], parent['formIndex'])

    def test_binding_metric_order_maps_back_to_render_order_by_identity(self):
        environment = 'sig A {s:set A}\nsig B {r:set B}\n'
        result = self.locate('all x:B, y:A | no x.r and no y.s', environment)
        bindings = [op for op in result['operations'] if op['component'] == 'quantifier']
        self.assertEqual(len(bindings), 2)
        spans = [self.span(result, op) for op in bindings]
        self.assertTrue(bindings[0]['expected'].endswith(' A'))
        self.assertTrue(bindings[1]['expected'].endswith(' B'))
        # The canonical metric sorts same-kind bindings by type, while the
        # displayed prefix keeps the original binding list. Index arithmetic
        # against printed order alone would select the other declaration.
        self.assertGreater(spans[0]['start'], spans[1]['start'])

    def test_insertion_anchor_selects_the_existing_node_without_target_expression(self):
        result = self.locate('some (A.r) and no (A.s)')
        join = next(op for op in result['operations'] if op.get('expected') == '(A . s)')
        operation = {'kind': 'insert', 'component': 'matrix', 'path': join['path'],
                     'sourceRole': 'insertion-anchor', 'sourceTerm': 'WRONG_TEXT',
                     'targetExpression': 'PRIVATE_TARGET_NOT_USED'}
        result = self.locate('some (A.r) and no (A.s)', operations=[operation])
        span = self.span(result, result['operations'][0])
        self.assertEqual(utf16_slice(result['forms'][0], span['start'], span['end']), '(A . s)')
        self.assertIn('anchor', result['operations'][0]['canonicalLocation']['reason'])
        self.assertNotIn('PRIVATE', json.dumps(result['operations'][0]['canonicalLocation']))

    def test_missing_quantifier_has_form_context_and_no_fake_existing_node(self):
        operation = {'kind': 'insert', 'component': 'quantifier', 'path': 'normalForm[0].quantifier[1]'}
        result = self.locate('all x:A | no x.r', operations=[operation])
        location = result['operations'][0]['canonicalLocation']
        self.assertEqual(location['precision'], 'form')
        self.assertEqual(location['ranges'], [{'formIndex': 0, 'start': 0, 'end': len(result['forms'][0])}])

    def test_temporal_and_aggregate_operations_keep_explicit_form_context(self):
        operations = [{'kind': 'replace', 'component': 'temporal', 'path': 'temporal'},
                      {'kind': 'component-edit', 'component': 'matrix', 'path': 'matrix', 'aggregate': True}]
        result = self.locate('always no r', operations=operations)
        for operation in result['operations']:
            location = operation['canonicalLocation']
            self.assertEqual(location['precision'], 'form')
            self.assertEqual(len(location['ranges']), len(result['forms']))
            for span in location['ranges']:
                self.assertEqual(span['start'], 0)
                self.assertEqual(span['end'], len(result['forms'][span['formIndex']]))

    def test_invalid_paths_and_nonexistent_learner_phase_never_fall_back_to_text_matching(self):
        operations = [{'kind': 'replace', 'component': 'matrix', 'sourceTerm': 'A', 'path': path}
                      for path in ['normalForm[0].matrix.child[999]', 'normalForm[99].matrix',
                                   'normalForm[0].matrix.child[-1]', 'normalForm[999999999999999999999].matrix',
                                   'matrix', 'normalForm[0].matrix.extra']]
        operations += [{'kind': 'insert', 'component': 'quantifier', 'path': 'normalForm[0].quantifier[999]'}]
        result = self.locate('some A', operations=operations)
        for operation in result['operations']:
            self.assertEqual(operation['canonicalLocation']['status'], 'unavailable')
            self.assertEqual(operation['canonicalLocation']['ranges'], [])

    def test_renderer_mismatch_and_wrong_form_count_fail_closed(self):
        for forms in [['root normal form := target((SOME WRONG))'], []]:
            result = self.locate('some A', forms=forms)
            self.assertTrue(result['operations'])
            for operation in result['operations']:
                self.assertEqual(operation['canonicalLocation']['status'], 'unavailable')

    def test_uppercase_names_do_not_get_confused_with_operator_tokens(self):
        result = self.locate('some NO and no SOME', 'sig NO, SOME {}\n')
        names = [op for op in result['operations'] if op.get('expected') in ('NO', 'SOME')]
        self.assertEqual(len(names), 2)
        for operation in names:
            self.span(result, operation)

    def test_residual_quantifiers_preserve_bodies_and_locate_all_children(self):
        for quantifier in ('one', 'lone', 'no'):
            with self.subTest(quantifier=quantifier):
                result = self.locate(f'some A and ({quantifier} a:A | some a.r)')
                nodes = [op for op in result['operations']
                         if op.get('node') == quantifier.upper() and op.get('arity', 0) > 1]
                self.assertEqual(len(nodes), 1, 'fixture must exercise a residual quantified node')
                quantified = nodes[0]
                self.assertIn('(SOME (a . r))', quantified['expected'])
                body = next(op for op in result['operations']
                            if op['path'] == quantified['path'] + '.child[1]')
                self.assertEqual(body['expected'], '(SOME (a . r))')
                spans = {op['path']: self.span(result, op) for op in result['operations']}
                for path, span in spans.items():
                    if '.child[' in path:
                        parent = spans[path.rsplit('.child[', 1)[0]]
                        self.assertGreaterEqual(span['start'], parent['start'])
                        self.assertLessEqual(span['end'], parent['end'])

    def test_residual_body_change_has_distinct_rendering_and_exact_edit_location(self):
        environment = 'sig A {r, s: set A}\n'
        for quantifier in ('one', 'lone', 'no'):
            with self.subTest(quantifier=quantifier):
                before = f'some A and ({quantifier} a:A | some a.r)'
                after = f'some A and ({quantifier} a:A | no a.r)'
                student = environment + 'pred target {\n' + before + '\n}\n'
                reference = environment + 'pred target {\n' + after + '\n}\n'
                result = self.locate(before, reference=reference)
                reverse = self.locate(after, reference=student)
                self.assertNotEqual(result['forms'], reverse['forms'])
                feedback = result['feedback']
                self.assertEqual(feedback['status'], 'ok')
                self.assertEqual(feedback['distance'], 1, 'renderer must not change the metric')
                self.assertEqual(reverse['feedback']['distance'], 1)
                self.assertEqual(len(feedback['operations']), 1)
                operation = feedback['operations'][0]
                self.assertEqual(operation['sourceOperator'], 'some')
                self.assertEqual(operation['replacementOperator'], 'no')
                self.assertTrue(feedback['trace']['matrixReplayVerified'])
                span = self.span(result, operation)
                self.assertEqual(utf16_slice(result['forms'][span['formIndex']],
                                            span['start'], span['end']), '(SOME (a . r))')
                raw = operation['sourceLocation']
                self.assertEqual((raw['status'], raw['precision']), ('located', 'node'))
                self.assertEqual(len(raw['ranges']), 1)
                raw_span = raw['ranges'][0]
                self.assertEqual(utf16_slice(student, raw_span['start'], raw_span['end']), 'some a.r')

    def test_nested_residual_quantifiers_and_multiple_declarations_keep_every_occurrence(self):
        result = self.locate('some A and (one a, b:A | lone c:A | some a.r and c in b.r)')
        residual = [op for op in result['operations']
                    if op.get('node') in ('ONE', 'LONE') and op.get('arity', 0) > 1]
        self.assertGreaterEqual(len(residual), 2)
        paths = {op['path']: self.span(result, op) for op in result['operations']}
        for quantified in residual:
            for child in range(quantified['arity']):
                self.assertIn(quantified['path'] + f'.child[{child}]', paths)
        repeated = [op for op in result['operations'] if op.get('expected') == 'A']
        self.assertGreaterEqual(len(repeated), 3)
        self.assertEqual(len({paths[op['path']]['start'] for op in repeated}), len(repeated))

    def test_one_branch_swap_equal_display_retains_binding_cost_and_occurrence_locations(self):
        environment = 'sig A {}\n'
        branches = ('(one a:A | some a)', '(all b:A | some b)')
        bodies = [' and '.join(branches), ' and '.join(reversed(branches))]
        sources = [environment + 'pred target {\n' + body + '\n}\n' for body in bodies]
        results = [self.locate(body, environment, reference=sources[1-i],
                               diagnosticMapping={'_l0': '_l1', '_l1': '_l0'})
                   for i, body in enumerate(bodies)]
        self.assertEqual(results[0]['forms'], results[1]['forms'])
        for i, result in enumerate(results):
            with self.subTest(direction=i):
                bindings = {binding['originalName']: binding for binding in result['localBindings']}
                self.assertEqual(set(bindings), {'a', 'b'})
                self.assertEqual(bindings['a']['quantifier'], 'ONE')
                self.assertEqual(bindings['a']['name'], f'_l{i}')
                self.assertEqual(bindings['a']['bindingPath'],
                                 f'root/predicate[0]/and[{i}]/local-decl[0]')
                self.assertEqual(bindings['b']['quantifier'], 'ALL')
                self.assertEqual(bindings['b']['name'], f'_l{1-i}')
                self.assertEqual(bindings['b']['bindingPath'],
                                 f'root/predicate[0]/and[{1-i}]/local-decl[0]')
                feedback = result['feedback']
                self.assertEqual(feedback['status'], 'ok')
                self.assertEqual(feedback['distance'], 2)
                self.assertEqual(feedback['breakdown'], {'temporal': 0, 'quantifier': 0, 'matrix': 2})
                self.assertTrue(feedback['trace']['matrixReplayVerified'])
                # Diagnostic correspondence is not an authorized production mapping.
                self.assertEqual(result['explicitMappingMatrixCost'], 0)
                operations = feedback['operations']
                self.assertEqual(len(operations), 2)
                paths = {op['path']: op for op in result['operations']}
                canonical_starts, raw_precisions = set(), set()
                for operation in operations:
                    self.assertEqual((operation['kind'], operation['sourceTerm'], operation['cost']),
                                     ('replace', 'a', 1))
                    span = self.span(result, operation)
                    self.assertEqual(utf16_slice(result['forms'][span['formIndex']],
                                                span['start'], span['end']), 'a')
                    self.assertEqual(span, self.span(result, paths[operation['path']]))
                    canonical_starts.add(span['start'])
                    raw = operation['sourceLocation']
                    self.assertEqual(raw['status'], 'located')
                    self.assertEqual(len(raw['ranges']), 1)
                    raw_precisions.add(raw['precision'])
                    raw_span = raw['ranges'][0]
                    selected = utf16_slice(sources[i], raw_span['start'], raw_span['end'])
                    if operation['path'].endswith('.child[1].child[1].child[0]'):
                        self.assertEqual(raw['precision'], 'node')
                        self.assertEqual(selected, 'a')
                        self.assertEqual(raw_span['start'], sources[i].index('some a') + len('some '))
                    else:
                        # Declaration provenance is enclosing context, not an exact token.
                        self.assertEqual(raw['precision'], 'related')
                        self.assertEqual(selected, 'one a:A | some a')
                self.assertEqual(len(canonical_starts), 2)
                self.assertEqual(raw_precisions, {'node', 'related'})
                identity = self.locate(bodies[i], environment, reference=sources[i])['feedback']
                self.assertEqual(identity['distance'], 0)
                self.assertEqual(identity['operations'], [])

    def test_one_binding_path_change_alone_does_not_imply_positive_distance(self):
        bodies = ['some A and (one a:A | some a.r)', '(one a:A | some a.r) and some A']
        environment = 'sig A {r: set A}\n'
        sources = [environment + 'pred target {\n' + body + '\n}\n' for body in bodies]
        results = [self.locate(body, environment, reference=sources[1-i])
                   for i, body in enumerate(bodies)]
        self.assertEqual(results[0]['forms'], results[1]['forms'])
        self.assertNotEqual(results[0]['localBindings'][0]['bindingPath'],
                            results[1]['localBindings'][0]['bindingPath'])
        for result in results:
            self.assertEqual(result['feedback']['distance'], 0)
            self.assertEqual(result['feedback']['operations'], [])

    def test_actual_unary_operator_rendering_is_unchanged(self):
        for operator in ('some', 'no', 'one', 'lone'):
            with self.subTest(operator=operator):
                result = self.locate(operator + ' A')
                self.assertEqual(result['forms'], [f'root normal form := target(({operator.upper()} A))'])
                for operation in result['operations']:
                    self.span(result, operation)
