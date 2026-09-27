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
      .put("expected", (String) render.invoke(null, node)).put("node", node.getOpcode().name()));
    for (int i=0;i<node.getChildren().size();i++) walk(node.getChildren().get(i), path+".child["+i+"]");
  }
  public static void main(String[] args) throws Exception {
    var wire = System.out;
    System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
    System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
    JSONObject request = new JSONObject(new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    Canonical.Prepared learner = LiveFeedback.prepare(request.getString("source"), "target");
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
    wire.println(new JSONObject().put("forms", forms).put("operations", operations));
  }
}
'''


def utf16_slice(value, start, end):
    return value.encode('utf-16-le')[start * 2:end * 2].decode('utf-16-le')


class CanonicalStructureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix='canonical-structure-')
        cls.addClassCleanup(cls.directory.cleanup)
        source = Path(cls.directory.name) / 'CanonicalStructureHarness.java'
        source.write_text(HARNESS, encoding='utf-8')
        classpath = os.pathsep.join((str(ROOT / 'build/engine/classes'), str(ROOT / 'vendor/acgn/lib/*')))
        compiled = subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath,
                                   '-d', cls.directory.name, str(source)], capture_output=True, timeout=25)
        if compiled.returncode:
            raise AssertionError('Canonical structure harness did not compile')
        cls.command = ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
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
