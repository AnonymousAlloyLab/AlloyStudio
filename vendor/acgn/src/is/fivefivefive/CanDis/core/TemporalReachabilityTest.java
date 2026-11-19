package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import parser.ast.nodes.ModelUnit;

/** Whole-reference reachability, with original parser authorities retained. */
public final class TemporalReachabilityTest {
    private static int checks, rejections;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void reject(Runnable action, String message) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { rejections++; return; }
        throw new AssertionError("Accepted invalid evidence: " + message);
    }
    private static List<NormalForm> prepare(CompModule module, String predicate) {
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of(predicate), module);
        visitor.visit(new ModelUnit(null, module), null);
        var agent = new IRAgent(visitor.getForest().get(visitor.getForestId(predicate)));
        agent.computeNormalForm();
        return List.copyOf(agent.normalForms());
    }
    private static List<EGraphNode> references(EGraphNode root) {
        var result = new ArrayList<EGraphNode>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<EGraphNode, Boolean>());
        var pending = new ArrayDeque<EGraphNode>();
        if (root != null) pending.add(root);
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (!seen.add(node)) continue;
            if (node.temporalReferenceAuthorityId() > 0) result.add(node);
            pending.addAll(node.getChildren());
        }
        return result;
    }
    private static JSONObject checkProjection(List<NormalForm> forms) {
        var evidence = TheoryAlloyAdapter.adapt(forms);
        evidence.requireRepairProjectionSources(forms);
        forms.get(0).requireAdmittedTemporalTree();
        checks++;
        var rows = new JSONArray();
        for (var owner : forms) {
            for (var projection : owner.getTemporalReferenceProjections()) {
                var original = owner.getSourceTemporalChildren();
                boolean oldProjectedBoundRejects = projection.sourceSlot() + projection.branches().size()
                        > owner.getTemporalChildren().size();
                boolean originalBoundAccepts = projection.sourceSlot() + projection.branches().size() <= original.size();
                check(originalBoundAccepts, "parser-source child range remains valid");
                if (owner.getTemporalChildren().isEmpty()) {
                    check(oldProjectedBoundRejects, "exact old projected-count gate rejects retained source occurrence");
                }
                for (int role = 0; role < projection.branches().size(); role++) {
                    check(original.get(projection.sourceSlot() + role) == projection.branches().get(role),
                            "exact original branch object and role retained");
                }
                var liveRefs = new ArrayList<EGraphNode>(references(owner.getMatrixEGraph()));
                liveRefs.addAll(references(owner.getCertificationMatrixEGraph()));
                if (projection.normalizedSlot() < 0) {
                    check(liveRefs.stream().noneMatch(r -> r.temporalReferenceAuthorityId() == projection.authorityId()),
                            "retired occurrence absent from both matrices");
                    check(projection.branches().stream().noneMatch(forms::contains), "retired phases not executed");
                    check(projection.branches().stream().allMatch(NormalForm::isFrozenForCertification),
                            "retired source records frozen, not erased");
                } else {
                    check(liveRefs.stream().anyMatch(r -> r.temporalReferenceAuthorityId() == projection.authorityId()),
                            "live occurrence not lost");
                    for (var ref : liveRefs) if (ref.temporalReferenceAuthorityId() == projection.authorityId()) {
                        var origin = owner.temporalReferenceOrigin(ref);
                        check(origin.owner() == owner && origin.parserOccurrenceId() == projection.parserOccurrence()
                                && origin.sourceOccurrenceLineage() == projection.sourceOccurrence(), "exact source origin retained");
                        check(origin.sourceSlot() == projection.normalizedSlot(), "checked normalized ordinal");
                        for (int role = 0; role < origin.branches().size(); role++) {
                            check(origin.branches().get(role) == projection.branches().get(role)
                                    && owner.getTemporalChildren().get(projection.normalizedSlot() + role)
                                        == projection.branches().get(role), "ordered group roundtrip");
                        }
                    }
                }
                rows.put(new JSONObject().put("ownerPhase", owner.getPhasePath())
                        .put("authority", projection.authorityId()).put("parserOccurrence", projection.parserOccurrence())
                        .put("sourceOccurrence", projection.sourceOccurrence()).put("sourceSlot", projection.sourceSlot())
                        .put("normalizedSlot", projection.normalizedSlot())
                        .put("oldProjectedBoundRejects", oldProjectedBoundRejects)
                        .put("originalBoundAccepts", originalBoundAccepts)
                        .put("branches", projection.branches().stream().map(x -> x.getPhasePath() + ":" + x.getTemporalOp()).toList()));
            }
        }
        return new JSONObject().put("activePhases", forms.size()).put("projections", rows)
                .put("sourceForms", CanonicalDistance.sourceIrTemporalFol(forms));
    }
    private static Object replace(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        var prior = field.get(object);
        field.set(object, value);
        return prior;
    }
    public static void main(String[] args) throws Exception {
        var input = new JSONArray(Files.readString(Path.of(args[0])));
        var result = new JSONArray();
        for (Object item : input) {
            var row = (JSONObject) item;
            var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, Files.readString(Path.of(row.getString("source"))));
            for (Object name : row.getJSONArray("predicates")) {
                var forms = prepare(module, (String) name);
                result.put(checkProjection(forms).put("source", row.getString("source")).put("predicate", name));
            }
        }
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, """
                var sig A {}
                var sig B in A {}
                pred unary { ((some A and no A) implies after some A) and eventually some A }
                pred binary { ((some A and no A) implies after some A) and ((some A) until (no A)) }
                pred nested { ((some A and no A) implies after (always some A)) and eventually some A }
                pred bound { all a:A | always a in B }
                """);
        for (String name : List.of("unary", "binary", "nested")) {
            var forms = prepare(module, name);
            result.put(checkProjection(forms).put("synthetic", name));
            var projections = forms.get(0).getTemporalReferenceProjections();
            check(projections.stream().anyMatch(x -> x.normalizedSlot() < 0), "unused group retired");
            check(projections.stream().anyMatch(x -> x.sourceSlot() == 1 && x.normalizedSlot() == 0), "live group relocated");
            if (name.equals("binary")) {
                var children = forms.get(0).getTemporalChildren();
                check(children.size() == 2 && children.get(0).getTemporalOp() == NormalForm.TemporalOp.UNTILL
                        && children.get(1).getTemporalOp() == NormalForm.TemporalOp.UNTILR, "binary ordered roles atomic");
            }
            var foreign = prepare(module, name);
            checkProjection(foreign);
            var owner = forms.get(0);
            var previous = replace(owner, "matrixEGraphRoot", foreign.get(0).getMatrixEGraph());
            reject(owner::requireAdmittedTemporalTree, "same-shaped foreign owner matrix");
            replace(owner, "matrixEGraphRoot", previous);
            var ref = references(owner.getMatrixEGraph()).get(0);
            var originalName = replace(ref, "sourceName", "temporal[999:1]");
            reject(owner::requireAdmittedTemporalTree, "wrong normalized slot");
            replace(ref, "sourceName", originalName);
            var sourceChildren = owner.getSourceTemporalChildren();
            var reversed = new ArrayList<>(sourceChildren); Collections.reverse(reversed);
            var originalChildren = replace(owner, "sourceTemporalChildren", reversed);
            reject(owner::requireAdmittedTemporalTree, "wrong original branch object/order");
            replace(owner, "sourceTemporalChildren", originalChildren);
            var child = owner.getTemporalChildren().get(0);
            var originalOp = replace(child, "temporalOp", NormalForm.TemporalOp.BEFORE);
            reject(owner::requireAdmittedTemporalTree, "wrong live temporal role");
            replace(child, "temporalOp", originalOp);
            owner.requireAdmittedTemporalTree(); checks++;
        }
        var bound = prepare(module, "bound");
        checkProjection(bound);
        var child = bound.get(0).getTemporalChildren().get(0);
        check(!child.getInheritedQuantiVars().isEmpty(), "live binder remains imported");
        var pending = new ArrayDeque<EGraphNode>();
        pending.add(child.getCertificationMatrixEGraph());
        EGraphNode variable = null;
        while (!pending.isEmpty() && variable == null) {
            var node = pending.removeFirst();
            if (node.getOpcode() == EGraphNode.Opcode.VARIABLE) variable = node;
            else pending.addAll(node.getChildren());
        }
        check(variable != null, "actual live variable occurrence found");
        var alpha = replace(variable, "alphaName", "@foreign:unbound");
        var sourceName = replace(variable, "sourceName", "foreign_unbound");
        reject(() -> TheoryAlloyAdapter.adapt(bound), "actual live unbound variable rejected");
        replace(variable, "alphaName", alpha);
        replace(variable, "sourceName", sourceName);
        var output = new JSONObject().put("checks", checks).put("negativeControls", rejections)
                .put("rows", result).put("nativeRepairPaths", "SEPARATELY_CAPTURED_BY_QUEUE_PROBE")
                .put("publicCertification", "BLOCKED");
        Files.writeString(Path.of(args[1]), output.toString(2), StandardOpenOption.CREATE_NEW);
        System.out.println("TemporalReachabilityTest: " + checks + " checks, " + rejections + " invalid controls");
    }
}
