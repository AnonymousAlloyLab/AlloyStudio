"""Parser-position provenance through the actual Fast Rewrite normalization."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from runtime_dependencies import JAR_FILES


ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''
package is.fivefivefive.CanDis;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.QuantiVar;
import java.util.*;
import org.json.*;
import parser.ast.nodes.ModelUnit;
public final class SourceOriginProbe {
    static JSONObject origin(EGraphNode.SourceOrigin value) {
        return value == null ? null : new JSONObject().put("x", value.x()).put("y", value.y())
                .put("x2", value.x2()).put("y2", value.y2());
    }
    static void visit(EGraphNode node, String path, JSONArray out) {
        if (node == null) return;
        JSONObject item = new JSONObject().put("path", path).put("opcode", node.getOpcode().name())
                .put("name", Objects.toString(node.getSourceName(), ""));
        JSONObject source = origin(node.getSourceOrigin());
        item.put("origin", source == null ? JSONObject.NULL : source);
        out.put(item);
        for (int i = 0; i < node.getChildren().size(); i++)
            visit(node.getChildren().get(i), path + ".child[" + i + "]", out);
    }
    static void findA(EGraphNode node, List<EGraphNode> matches) {
        if (node.getOpcode() == EGraphNode.Opcode.GLOBALBINDING && "A".equals(node.getSourceName()))
            matches.add(node);
        for (EGraphNode child : node.getChildren()) findA(child, matches);
    }
    static Multigraph graph(String source, boolean eraseOrigins) throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), Set.of("p"), module);
        visitor.visit(MASGVisitor.modelWithSourceMap(module), null);
        Multigraph graph = visitor.getForest().get(visitor.getForestId("p"));
        if (eraseOrigins) {
            java.lang.reflect.Field field = is.fivefivefive.ACGN.asg.AugmentedNode.class.getDeclaredField("sourceOrigins");
            field.setAccessible(true);
            for (var node : graph.getVertices()) field.set(node, null);
        }
        return graph;
    }
    static Canonical.Prepared prepare(String source, boolean eraseOrigins) throws Exception {
        return Canonical.prepare(graph(source, eraseOrigins));
    }
    public static void main(String[] args) throws Exception {
        String source = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        java.io.PrintStream output = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        Canonical.Prepared prepared = prepare(source, false);
        Canonical.Prepared withoutOrigins = prepare(source, true);
        JSONArray nodes = new JSONArray(), bindings = new JSONArray();
        int phase = 0;
        for (NormalForm form : prepared.normalizedForms()) {
            visit(form.getMatrixEGraph(), "normalForm[" + phase + "].matrix", nodes);
            for (QuantiVar binding : form.getMatrixQuantiVars()) {
                JSONObject position = origin(binding.getSourceOrigin());
                bindings.put(new JSONObject().put("name", binding.getOriginalName())
                        .put("origin", position == null ? JSONObject.NULL : position));
            }
            phase++;
        }
        boolean unionUnavailable = false;
        if (args.length > 0 && args[0].equals("union")) {
            is.fivefivefive.CanDis.ir.IRAgent unfrozen = new is.fivefivefive.CanDis.ir.IRAgent(graph(source, false));
            unfrozen.computeNormalForm();
            List<EGraphNode> matches = new ArrayList<>();
            findA(unfrozen.normalForms().get(0).getMatrixEGraph(), matches);
            if (matches.size() != 2 || matches.get(0).getSourceOrigin() == null
                    || matches.get(1).getSourceOrigin() == null)
                throw new AssertionError("Union fixture must start with two recorded occurrences");
            EGraphNode.EClassRef combined = EGraphNode.union(matches.get(0).getEClassRef(), matches.get(1).getEClassRef());
            unionUnavailable = matches.get(0).getSourceOrigin() == null && matches.get(1).getSourceOrigin() == null
                    && combined.getEClass().getRepresentative().getSourceOrigin() == null;
        }
        output.println(new JSONObject().put("nodes", nodes).put("bindings", bindings)
                .put("unionUnavailable", unionUnavailable)
                .put("forms", Canonical.irTemporalFol(prepared))
                .put("metadataDistance", Canonical.distance(prepared, withoutOrigins))
                .put("sameFormsWithoutMetadata", Canonical.irTemporalFol(prepared)
                        .equals(Canonical.irTemporalFol(withoutOrigins))));
    }
}
'''


class SourceOriginMetadataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='alloy-source-origin-')
        cls.addClassCleanup(cls.temporary.cleanup)
        directory = Path(cls.temporary.name)
        source = directory / 'SourceOriginProbe.java'
        source.write_text(HARNESS, encoding='utf-8')
        cls.java = shutil.which('java')
        compiler = shutil.which('javac')
        if not cls.java or not compiler:
            raise RuntimeError('Source-origin verification requires a Java 17+ JDK.')
        dependencies = os.pathsep.join(str(ROOT / 'vendor/acgn/lib' / name) for name in JAR_FILES)
        compiled = subprocess.run([compiler, '--release', '17', '-encoding', 'UTF-8',
                                   '-Xprefer:source', '-cp', dependencies,
                                   '-sourcepath', str(ROOT / 'vendor/acgn/src'),
                                   '-d', str(directory), str(source)],
                                  capture_output=True, text=True, timeout=120)
        if compiled.returncode:
            raise AssertionError(compiled.stderr)
        cls.classpath = os.pathsep.join((str(directory), dependencies))

    def probe(self, source, *args):
        process = subprocess.run([self.java, '-Xmx256m', '-XX:ActiveProcessorCount=2',
                                  '-cp', self.classpath,
                                  'is.fivefivefive.CanDis.SourceOriginProbe', *args],
                                 input=source, capture_output=True, text=True, timeout=30)
        self.assertEqual(process.returncode, 0, process.stderr)
        result = json.loads(process.stdout)
        self.assertEqual(result['metadataDistance'], 0)
        self.assertTrue(result['sameFormsWithoutMetadata'])
        return result

    @staticmethod
    def span(source, item):
        position = item['origin']
        if position is None:
            return None
        lines = source.splitlines(keepends=True)
        start = sum(len(line) for line in lines[:position['y'] - 1]) + position['x'] - 1
        end = sum(len(line) for line in lines[:position['y2'] - 1]) + position['x2']
        return source[start:end]

    def test_same_atom_in_different_ordered_parents_keeps_use_site(self):
        source = 'sig A { r: set A }\npred p { A.r = r.A }'
        result = self.probe(source)
        leaves = [node for node in result['nodes'] if node['name'] == 'A']
        self.assertEqual(len(leaves), 2, result)
        self.assertEqual([self.span(source, leaf) for leaf in leaves], ['A', 'A'])
        self.assertEqual(len({tuple(leaf['origin'].values()) for leaf in leaves}), 2)
        self.assertTrue(all(leaf['origin']['y'] == 2 for leaf in leaves))

    def test_reordered_operands_retain_original_operator_spans(self):
        source = 'sig A {} sig B {}\npred p { some B or some A }'
        result = self.probe(source)
        operands = [node for node in result['nodes'] if node['opcode'] == 'SOME']
        self.assertEqual({self.span(source, node) for node in operands}, {'some A', 'some B'})
        for node in operands:
            child = next(item for item in result['nodes'] if item['path'] == node['path'] + '.child[0]')
            self.assertEqual(self.span(source, node), 'some ' + child['name'])

    def test_identical_joins_under_distinct_parents_keep_each_leaf_occurrence(self):
        source = 'sig A { r: set A }\npred p { some A.r or lone A.r }'
        result = self.probe(source)
        joins = [node for node in result['nodes'] if node['opcode'] == 'JOIN']
        self.assertEqual(len(joins), 2)
        starts = set()
        for join in joins:
            self.assertEqual(self.span(source, join), 'A.r')
            starts.add(join['origin']['x'])
            for offset, text in ((0, 'A'), (1, 'r')):
                child = next(node for node in result['nodes'] if node['path'] == join['path'] + f'.child[{offset}]')
                self.assertEqual(self.span(source, child), text)
                self.assertEqual(child['origin']['x'], join['origin']['x'] + offset * 2)
        self.assertEqual(starts, {15, 27})

    def test_alpha_renamed_variables_keep_separate_use_sites(self):
        source = 'sig A { r: set A }\npred p { all x: A | x.r = r.x }'
        result = self.probe(source)
        variables = [node for node in result['nodes'] if node['opcode'] == 'VARIABLE']
        self.assertEqual(len(variables), 2)
        self.assertEqual({node['origin']['x'] for node in variables}, {21, 29})
        self.assertEqual([self.span(source, node) for node in variables], ['x', 'x'])

    def test_temporal_phase_operand_keeps_its_parser_span(self):
        source = 'var sig A {}\npred p { always some A }'
        result = self.probe(source)
        some = [node for node in result['nodes'] if node['opcode'] == 'SOME']
        self.assertEqual(len(some), 1)
        self.assertEqual(self.span(source, some[0]), 'some A')
        self.assertTrue(some[0]['path'].startswith('normalForm[1].'))

    def test_explicit_union_does_not_choose_one_conflicting_occurrence(self):
        result = self.probe('sig A { r: set A }\npred p { A.r = r.A }', 'union')
        self.assertTrue(result['unionUnavailable'])

    def test_duplicate_elimination_keeps_the_retained_source_occurrence(self):
        source = 'sig A {}\npred p { some A and some A }'
        result = self.probe(source)
        some = [node for node in result['nodes'] if node['opcode'] == 'SOME']
        self.assertEqual(len(some), 1)
        self.assertEqual(self.span(source, some[0]), 'some A')
        self.assertEqual(some[0]['origin']['x'], 10)

    def test_double_negation_retains_inner_source_expression(self):
        source = 'sig A {}\npred p { not not some A }'
        result = self.probe(source)
        some = [node for node in result['nodes'] if node['opcode'] == 'SOME']
        self.assertEqual(len(some), 1)
        self.assertEqual(self.span(source, some[0]), 'some A')

    def test_generated_connective_has_no_fabricated_source_occurrence(self):
        source = 'sig A {} sig B {}\npred p { some A implies some B }'
        result = self.probe(source)
        generated = [node for node in result['nodes'] if node['opcode'] == 'OR']
        self.assertTrue(generated)
        self.assertTrue(all(node['origin'] is None for node in generated))
        some = [node for node in result['nodes'] if node['opcode'] == 'SOME']
        self.assertIn('some B', [self.span(source, node) for node in some])

    def test_lifted_binding_remembers_its_quantifier_context(self):
        source = 'sig A { r: set A }\npred p { all x: A | some x.r }'
        result = self.probe(source)
        self.assertEqual(len(result['bindings']), 1, result)
        self.assertEqual(self.span(source, result['bindings'][0]), 'all x: A | some x.r')

    def test_merged_bindings_do_not_inherit_an_arbitrary_first_span(self):
        source = 'sig A { r: set A }\npred p { (all x: A | some x.r) and (all y: A | no y.r) }'
        result = self.probe(source)
        self.assertEqual(len(result['bindings']), 1, result)
        self.assertIsNone(result['bindings'][0]['origin'])


if __name__ == '__main__':
    unittest.main()
