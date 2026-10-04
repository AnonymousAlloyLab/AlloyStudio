package is.fivefivefive.CanDis.theory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import parser.ast.nodes.ModelUnit;

/** Retained-source observations. No field writes, forged evidence or publication. */
public final class SourceOccurrenceBindingsRegressionTest {
    private static final List<String> ROWS = new ArrayList<>();
    private static int nextId, checks;
    private static final SemanticProfile PROFILE = SemanticProfile.alloyOverflowForbidding();

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void row(String fixture, String field, String value) {
        ROWS.add(fixture + "\t" + field + "\t" + b64(value));
    }

    private static EGraphNode leaf(String name, int width) {
        EGraphNode n = new EGraphNode(nextId++, Opcode.GLOBALBINDING, new ArrayList<>(),
                false, 0, false, Metatype.SET, PROFILE);
        n.setSourceName(name);
        n.setSourceType(name);
        n.setExactAlloyType(ExactAlloyType.relation(Collections.nCopies(width, "A")));
        return n;
    }

    private static EGraphNode node(Opcode op, EGraphNode... children) {
        boolean bool = op == Opcode.SOME || op == Opcode.AND;
        EGraphNode n = new EGraphNode(nextId++, op, new ArrayList<>(), op == Opcode.AND,
                op == Opcode.AND ? -1 : children.length, op == Opcode.AND,
                bool ? Metatype.BOOLEAN : Metatype.SET, PROFILE);
        int width = bool ? 0 : children[0].getExactAlloyType().relationArity()
                + children[1].getExactAlloyType().relationArity() - (op == Opcode.JOIN ? 2 : 0);
        n.setExactAlloyType(bool ? ExactAlloyType.boolType()
                : ExactAlloyType.relation(Collections.nCopies(width, "A")));
        for (EGraphNode child : children) n.addChild(child);
        return n;
    }

    private static EGraphNode chain(Opcode op, String variant) {
        int width = op == Opcode.JOIN ? 2 : 1;
        EGraphNode a = leaf("a", width), b = leaf(variant.equals("repeat") ? "a" : "b", width),
                c = leaf("c", width);
        return variant.equals("right") ? node(op, a, node(op, b, c)) : node(op, node(op, a, b), c);
    }

    private static NormalForm form(EGraphNode root) {
        NormalForm f = new NormalForm();
        f.addEClass(root);
        return f;
    }

    // Read-only invocation of the actual private indexer, never heap mutation.
    // Public adapt below must independently succeed before positive observations.
    @SuppressWarnings("unchecked")
    private static Map<EGraphNode, String> paths(List<NormalForm> forms) throws Exception {
        Class<?> builder = Class.forName("is.fivefivefive.CanDis.theory.TheoryAlloyAdapter$Builder");
        Method method = builder.getDeclaredMethod("indexSourceOccurrencePaths", List.class);
        method.setAccessible(true);
        try {
            return (Map<EGraphNode, String>) method.invoke(null, forms);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    private static String rejected(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("supported transition accepted");
        } catch (IllegalStateException | IllegalArgumentException e) {
            return e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    private static void fixture(Opcode op, String variant) throws Exception {
        EGraphNode.beginGraph();
        try {
            nextId = 1;
            String id = op + ":" + variant;
            EGraphNode source = chain(op, variant);
            NormalForm f = form(node(Opcode.SOME, source));
            f.normalize();
            TheoryAlloyAdapter.Result result = TheoryAlloyAdapter.adapt(List.of(f), PROFILE);
            Map<EGraphNode, String> indexed = paths(List.of(f));
            row(id, "paths", String.join("\n", new TreeSet<>(indexed.values())));
            var bindings = result.dependentChainSourceBindings();
            check(bindings.size() == 1, id + " binding count " + bindings.size());
            var entry = bindings.entrySet().iterator().next();
            EGraphNode repair = entry.getKey();
            var binding = entry.getValue();
            EGraphNode retained = indexed.entrySet().stream()
                    .filter(e -> e.getValue().equals(binding.sourceOccurrencePath()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            check(retained != repair, "normalization must retain a separate certification tree");
            binding.requireMatches(repair);
            row(id, "path", binding.sourceOccurrencePath());
            row(id, "typed", binding.certificate().source().structuralKey().stableString());
            row(id, "content", retained.dependentChainSourceContentCommitment());
            row(id, "repairContent", repair.dependentChainSourceContentCommitment());
            row(id, "commitment", binding.sourceOccurrenceCommitment().stableString());
            row(id, "certificate", binding.certificate().sourceOccurrenceCommitment().stableString());
            row(id, "sourceTransfer", retained.dependentChainTransferContentCommitment());
            row(id, "repairTransfer", repair.dependentChainTransferContentCommitment());
            row(id, "lineage", Boolean.toString(retained.getSourceOccurrenceLineage() > 0
                    && retained.getSourceOccurrenceLineage() == repair.getSourceOccurrenceLineage()));
            row(id, "separate", Boolean.toString(retained != repair));
            row(id, "matches", "ACCEPT");
            row(id, "rename", rejected(() -> repair.getChildren().get(0).setSourceName("changed")));
            row(id, "children", rejected(() -> repair.setChildren(List.of(repair.getChildren().get(1),
                    repair.getChildren().get(0)))));
            row(id, "type", rejected(() -> retained.setExactAlloyType(ExactAlloyType.unaryRelation("B"))));
            row(id, "projection", rejected(() -> result.requireRepairProjectionSources(List.of(new NormalForm()))));
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void occurrences(Opcode op, boolean equal) throws Exception {
        EGraphNode.beginGraph();
        try {
            nextId = 1;
            String id = op + (equal ? ":equal-occurrences" : ":different-occurrences");
            EGraphNode a = chain(op, "left"), b = chain(op, "left");
            if (!equal) b.getChildren().get(1).setSourceName("changed");
            NormalForm f = form(node(Opcode.AND, node(Opcode.SOME, a), node(Opcode.SOME, b)));
            // No normalization: equal-valued occurrences must both reach indexing.
            var result = TheoryAlloyAdapter.adapt(List.of(f), PROFILE);
            var bindings = new ArrayList<>(result.dependentChainSourceBindings().entrySet());
            bindings.sort(Comparator.comparing(e -> e.getValue().sourceOccurrencePath()));
            check(bindings.size() == 2, "two occurrence bindings");
            var first = bindings.get(0); var second = bindings.get(1);
            row(id, "paths", String.join("\n", new TreeSet<>(paths(List.of(f)).values())));
            row(id, "first", first.getValue().sourceOccurrencePath());
            row(id, "second", second.getValue().sourceOccurrencePath());
            row(id, "sameContent", Boolean.toString(first.getKey().dependentChainSourceContentCommitment()
                    .equals(second.getKey().dependentChainSourceContentCommitment())));
            row(id, "sameCommitment", Boolean.toString(first.getValue().sourceOccurrenceCommitment()
                    .equals(second.getValue().sourceOccurrenceCommitment())));
            row(id, "swap", rejected(() -> first.getValue().requireMatches(second.getKey())));
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void ownership() throws Exception {
        EGraphNode.beginGraph();
        try {
            nextId = 1;
            EGraphNode shared = leaf("a", 1);
            NormalForm f = form(node(Opcode.SOME, node(Opcode.ARROW, shared, shared)));
            row("ownership", "child", rejected(() -> TheoryAlloyAdapter.adapt(List.of(f), PROFILE)));
            NormalForm valid = form(node(Opcode.SOME, chain(Opcode.ARROW, "left")));
            NormalForm sameRoot = form(valid.getMatrixEGraph());
            row("ownership", "phase", rejected(() -> TheoryAlloyAdapter.adapt(List.of(valid, sameRoot), PROFILE)));
            row("ownership", "empty", String.join("\n", paths(List.of(new NormalForm())).values()));
            EGraphNode cycle = node(Opcode.SOME, leaf("cycle", 1));
            cycle.setChildren(List.of(cycle));
            row("ownership", "cycle", rejected(() -> TheoryAlloyAdapter.adapt(List.of(form(cycle)), PROFILE)));
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void provenance(Opcode op, boolean association) {
        EGraphNode.beginGraph();
        try {
            nextId = 1;
            NormalForm f = form(node(Opcode.SOME, chain(op, "left")));
            f.normalize();
            EGraphNode repair = f.getMatrixEGraph().getChildren().get(0);
            EGraphNode left = repair.getChildren().get(0), c = repair.getChildren().get(1);
            if (association) {
                EGraphNode a = left.getChildren().get(0), b = left.getChildren().get(1);
                repair.setChildren(List.of(a, node(op, b, c)));
            } else {
                c.setSourceName("changed");
            }
            row("provenance", op + (association ? "/association" : "/content"),
                    rejected(() -> TheoryAlloyAdapter.adapt(List.of(f), PROFILE)));
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void encoding() {
        EGraphNode.beginGraph();
        try {
            nextId = 1;
            String[] names = { "a:{}[];@", "\u03b1", "\ud835\udc00", "e\u0301" };
            for (int i = 0; i < names.length; i++) {
                EGraphNode source = node(Opcode.ARROW, leaf(names[i], 1), leaf("b", 1));
                var result = TheoryAlloyAdapter.adapt(List.of(form(node(Opcode.SOME, source))), PROFILE);
                var binding = result.dependentChainSourceBindings().get(source);
                check(binding != null, "encoding binding missing");
                binding.requireMatches(source);
                row("encoding", i + "/content", source.dependentChainSourceContentCommitment());
                row("encoding", i + "/wrapper", TheoryAlloyAdapter.DependentChainSourceBinding
                        .sourceOccurrenceCommitment(source, "phase/10/matrix/child/12",
                                StructuralKey.leaf("fixture", "typed")).stableString());
            }
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void parsed(String fixture, String body) throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A { r: set A } pred p { " + body + " }");
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        IRAgent agent = new IRAgent(visitor.getForest().get(visitor.getForestId("p")), PROFILE);
        agent.computeNormalForm();
        var forms = agent.normalForms();
        var result = TheoryAlloyAdapter.adapt(forms, PROFILE);
        var indexed = paths(forms);
        var bindings = new ArrayList<>(result.dependentChainSourceBindings().entrySet());
        bindings.sort(Comparator.comparing(e -> e.getValue().sourceOccurrencePath()));
        row(fixture, "phases", Integer.toString(forms.size()));
        row(fixture, "paths", String.join("\n", new TreeSet<>(indexed.values())));
        row(fixture, "bindings", String.join("\n", bindings.stream()
                .map(e -> e.getValue().sourceOccurrencePath()).toList()));
        for (int i = 0; i < bindings.size(); i++) {
            var entry = bindings.get(i);
            var binding = entry.getValue();
            EGraphNode retained = indexed.entrySet().stream()
                    .filter(e -> e.getValue().equals(binding.sourceOccurrencePath()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            binding.requireMatches(entry.getKey());
            String prefix = Integer.toString(i) + "/";
            row(fixture, prefix + "content", retained.dependentChainSourceContentCommitment());
            row(fixture, prefix + "repairContent", entry.getKey().dependentChainSourceContentCommitment());
            row(fixture, prefix + "sourceTransfer", retained.dependentChainTransferContentCommitment());
            row(fixture, prefix + "repairTransfer", entry.getKey().dependentChainTransferContentCommitment());
            row(fixture, prefix + "certifiedContent", binding.sourceOccurrenceCommitment().children().get(1).scalars().get(0));
            row(fixture, prefix + "certifiedPath", binding.sourceOccurrenceCommitment().scalars().get(0));
            row(fixture, prefix + "typedMatches", Boolean.toString(binding.sourceOccurrenceCommitment().children()
                    .get(0).children().get(0).equals(binding.certificate().source().structuralKey())));
            row(fixture, prefix + "lineage", Boolean.toString(retained.getSourceOccurrenceLineage()
                    == entry.getKey().getSourceOccurrenceLineage()));
            row(fixture, prefix + "matches", "ACCEPT");
        }
        if (fixture.equals("temporal")) {
            check(bindings.size() == 2, "two temporal bindings");
            row(fixture, "swap", rejected(() -> bindings.get(0).getValue().requireMatches(bindings.get(1).getKey())));
            List<NormalForm> reversed = new ArrayList<>(forms);
            Collections.reverse(reversed);
            row(fixture, "phaseOrder", rejected(() -> result.requireRepairProjectionSources(reversed)));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: SourceOccurrenceBindingsRegressionTest [OUTPUT.tsv]");
        check(Runtime.version().feature() == 17, "JDK17 required");
        Path output = args.length == 1 ? Path.of(args[0]) : null;
        if (output != null) Files.deleteIfExists(output);
        ROWS.clear(); ROWS.add("fixture\tfield\tvalue");
        try {
            for (Opcode op : List.of(Opcode.JOIN, Opcode.ARROW)) {
                for (String variant : List.of("left", "right", "repeat")) fixture(op, variant);
                occurrences(op, true);
                occurrences(op, false);
            }
            ownership();
            parsed("temporal", "some (r.r) and after some (r.r)");
            parsed("aci", "some ((r + r).r)");
            for (Opcode op : List.of(Opcode.JOIN, Opcode.ARROW)) {
                provenance(op, false);
                provenance(op, true);
            }
            encoding();
            if (output != null) Files.write(output, ROWS, StandardCharsets.UTF_8);
        } catch (Exception | AssertionError error) {
            if (output != null) Files.write(Path.of(output + ".failed.tsv"), ROWS, StandardCharsets.UTF_8);
            throw error;
        }
        System.out.println("SourceOccurrenceBindingsRegressionTest passed: rows=" + (ROWS.size() - 1) + " checks=" + checks);
    }
}
