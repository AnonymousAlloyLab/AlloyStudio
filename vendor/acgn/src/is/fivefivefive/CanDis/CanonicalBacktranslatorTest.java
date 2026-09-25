package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.NormalForm.TemporalOp;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

public final class CanonicalBacktranslatorTest {
    private CanonicalBacktranslatorTest() {
    }

    public static void main(String[] args) throws Exception {
        testQuantifiedNormalFormCompiles();
        testDisjointBindingGroupCompiles();
        testLocalComprehensionCompilesAsRelation();
        testTemporalNormalFormCompiles();
        System.out.println("CanonicalBacktranslatorTest passed");
    }

    private static void testQuantifiedNormalFormCompiles() throws Exception {
        NormalForm normalForm = new NormalForm();
        normalForm.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                relDeclOfType("Person", "x"),
                node(Opcode.IN, false, false, variable("x"), global("Student"))));
        normalForm.normalize();

        String module = module("canonical_backtranslation_quantified",
                "sig Person {}\nsig Student in Person {}\n",
                CanonicalBacktranslator.predicate("canonical_quantified", normalForm));
        assertCompiles(module);
        assertContains(module, "pred canonical_quantified[]", "predicate header must be emitted");
        assertContains(module, "all _q0: one Person",
                "canonical quantified variable must retain default-one cardinality");
        assertContains(module, "_q0 in Student", "alpha-normalized variable must be used in the matrix");
    }

    private static void testDisjointBindingGroupCompiles() throws Exception {
        NormalForm normalForm = new NormalForm();
        normalForm.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                disjRelDecl("x", "y"),
                node(Opcode.EQUALS, false, false, variable("x"), variable("y"))));
        normalForm.normalize();

        String module = module("canonical_backtranslation_disj",
                "sig S {}\n",
                CanonicalBacktranslator.predicate("canonical_disj", normalForm));
        assertCompiles(module);
        assertContains(module, "all disj _q0, _q1: one S",
                "variables from the same disjointness class must be emitted as one disj declaration");
    }

    private static void testTemporalNormalFormCompiles() throws Exception {
        Path sourceDirectory = Files.createTempDirectory("canonical-temporal-source-");
        Path sourcePath = sourceDirectory.resolve("temporal_source.als");
        List<NormalForm> forms;
        try {
            Files.writeString(
                    sourcePath,
                    "module temporal_source\n"
                            + "var sig S {}\n"
                            + "one sig s in S {}\n"
                            + "pred source { some S or after s in S }\n",
                    StandardCharsets.UTF_8);
            CompModule parsed = AlloyUtil.compileAlloyModule(sourcePath.toString());
            ModelUnit model = new ModelUnit(null, parsed);
            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), parsed);
            visitor.visit(model, null);
            Integer forestId = visitor.getForestId("source");
            if (forestId == null) {
                throw new AssertionError("temporal source predicate is missing from the MASG");
            }
            Multigraph graph = visitor.getForest().get(forestId);
            forms = Canonical.prepare(graph).normalizedForms();
        } finally {
            Files.deleteIfExists(sourcePath);
            Files.deleteIfExists(sourceDirectory);
        }

        String module = module("canonical_backtranslation_temporal",
                "var sig S {}\none sig s in S {}\n",
                CanonicalBacktranslator.predicate("canonical_temporal", forms));
        assertCompiles(module);
        assertContains(module, "after", "temporal child must be emitted with its temporal operator");
        if (module.contains("temporal[0:")) {
            throw new AssertionError(
                    "temporal references must be substituted in place:\n" + module);
        }
    }

    private static void testLocalComprehensionCompilesAsRelation() throws Exception {
        NormalForm normalForm = new NormalForm();
        EGraphNode comprehension = node(
                Opcode.COMPREHENSION,
                false,
                false,
                relDeclOfType("State", "s1", "s2"),
                node(
                        Opcode.IN,
                        false,
                        false,
                        variable("s2"),
                        node(Opcode.JOIN, false, true, variable("s1"), global("trans"))));
        normalForm.addEClass(node(
                Opcode.SOME,
                false,
                false,
                node(Opcode.CLOSURE, false, false, comprehension)));
        normalForm.normalize();

        String module = module("canonical_backtranslation_comprehension",
                "sig State { trans: set State }\n",
                CanonicalBacktranslator.predicate("canonical_comprehension", normalForm));
        assertCompiles(module);
        assertContains(module, "^{", "a local comprehension under closure must remain relational");
    }

    private static String module(String moduleName, String prelude, String predicate) {
        return "module " + moduleName + "\n\n" + prelude + "\n" + predicate + "\nrun "
                + predicateName(predicate) + " for 3\n";
    }

    private static String predicateName(String predicate) {
        int start = predicate.indexOf("pred ");
        int end = predicate.indexOf("[]", start);
        return predicate.substring(start + "pred ".length(), end).trim();
    }

    private static void assertCompiles(String source) throws Exception {
        Path file = Files.createTempFile("canonical-backtranslation-", ".als");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        AlloyUtil.compileAlloyModule(file.toString());
    }

    private static EGraphNode relDeclOfType(String typeName, String... variableNames) {
        EGraphNode[] children = new EGraphNode[variableNames.length + 1];
        children[0] = global(typeName);
        for (int i = 0; i < variableNames.length; i++) {
            EGraphNode declared = variable(variableNames[i]);
            declared.setSourceType(typeName);
            children[i + 1] = declared;
        }
        return node(Opcode.GENERICRELDECL, true, true, children);
    }

    private static EGraphNode disjRelDecl(String... variableNames) {
        EGraphNode[] children = new EGraphNode[variableNames.length + 1];
        children[0] = global("S");
        for (int i = 0; i < variableNames.length; i++) {
            EGraphNode declared = variable(variableNames[i]);
            declared.setSourceType("S");
            children[i + 1] = declared;
        }
        return node(Opcode.DISJ, true, true, children);
    }

    private static EGraphNode variable(String name) {
        EGraphNode variable = new EGraphNode(name.hashCode(), Opcode.VARIABLE, new ArrayList<>(), false, 0, false,
                Metatype.ATOMIC);
        variable.setSourceName(name);
        variable.setAlphaName(name);
        return variable;
    }

    private static EGraphNode global(String name) {
        EGraphNode binding = new EGraphNode(name.hashCode(), Opcode.GLOBALBINDING, new ArrayList<>(), false, 0,
                false, Metatype.SET);
        binding.setSourceName(name);
        binding.setSourceType(name);
        return binding;
    }

    private static EGraphNode node(
            Opcode opcode,
            boolean commutative,
            boolean flexible,
            EGraphNode... children) {
        return new EGraphNode(
                opcode.hashCode() + children.length,
                opcode,
                new ArrayList<>(Arrays.asList(children)),
                commutative,
                flexible ? -1 : children.length,
                flexible,
                Metatype.BOOLEAN);
    }

    private static void assertContains(String haystack, String needle, String message) {
        if (!haystack.contains(needle)) {
            throw new AssertionError(message + ": missing `" + needle + "` in\n" + haystack);
        }
    }
}
