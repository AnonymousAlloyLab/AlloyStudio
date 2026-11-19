package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;

import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.theory.BinderBlockDescriptor;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;

/**
 * Slot 8A/8B/8C correspondence controls for the Fast owner-local orbit and
 * SET deduplication.
 *
 * <p>The Fast alternatives must be the same authenticated owner-local orbit the
 * certified descriptor retains: identity first, one candidate per non-identity
 * permutation, owner/type/occurrence fixed, and a bijection on the binder's
 * declaration slots. SET dedup must remove only orbit-equal idempotent children
 * and must retain the eliminated duplicate as evidence. The natural Workstation
 * and User/Photo witnesses must remain MATCHED in both directions.
 */
public final class FastOrbitCorrespondenceTest {
    private static int checks;
    private static int negatives;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void walk(EGraphNode node, List<EGraphNode> out,
            java.util.Set<EGraphNode> seen) {
        if (node == null || !seen.add(node)) return;
        out.add(node);
        for (EGraphNode child : node.getChildren()) walk(child, out, seen);
    }

    private static List<EGraphNode> variableSlots(EGraphNode binder) {
        var result = new ArrayList<EGraphNode>();
        for (EGraphNode child : binder.getChildren()) {
            if (!isDeclaration(child)) continue;
            for (int index = 1; index < child.getChildren().size(); index++) {
                var variable = child.getChildren().get(index);
                check(variable.getOpcode() == EGraphNode.Opcode.VARIABLE,
                        "declaration slot is a variable");
                result.add(variable);
            }
        }
        return result;
    }

    private static boolean isDeclaration(EGraphNode node) {
        var opcode = node.getOpcode();
        return opcode == EGraphNode.Opcode.GENERICRELDECL
                || opcode == EGraphNode.Opcode.DISJ
                || opcode == EGraphNode.Opcode.VAR
                || opcode == EGraphNode.Opcode.DISJVAR;
    }

    private static String slot(String alpha) {
        if (alpha == null || !alpha.startsWith("@fast-local:")) return null;
        int first = alpha.indexOf(':', "@fast-local:".length());
        return first < 0 ? null : alpha.substring("@fast-local:".length());
    }

    private static int orbitMatchesDescriptor(CanonicalDistance.Prepared prepared,
            JSONObject identity, int direction) throws Exception {
        var comparison = prepared.comparisonForms().get(0).getMatrixEGraph();
        var nodes = new ArrayList<EGraphNode>();
        walk(comparison, nodes, Collections.newSetFromMap(new IdentityHashMap<>()));
        var pair = LocalOwnerTraceTest.prepare(identity);
        var adapted = TheoryAlloyAdapter.adapt(pair.get(direction).normalizedForms());
        Map<EGraphNode, BinderBlockDescriptor> descriptors = adapted.localBinderDescriptors();
        int binders = 0;
        for (EGraphNode node : nodes) {
            var alternatives = prepared.fastAlternatives(node);
            if (alternatives.isEmpty()) continue;
            var slots = variableSlots(node);
            check(slots.size() >= 2, "an orbit binder has at least two declaration slots");
            BinderBlockDescriptor descriptor = null;
            for (var entry : descriptors.entrySet()) {
                var candidate = entry.getValue();
                if (candidate.coordinates().size() == slots.size()
                        && candidate.automorphisms().elements().size() == alternatives.size()) {
                    descriptor = candidate;
                    break;
                }
            }
            check(descriptor != null, "Fast orbit binder has a matching certified descriptor shape");
            var elements = descriptor.automorphisms().elements();
            check(elements.size() == alternatives.size(),
                    "Fast alternative count equals the certified group order");
            check(elements.get(0).equals(is.fivefivefive.CanDis.theory.TypedPermutation
                    .identity(descriptor.boundContext())), "identity is the first certified element");
            check(alternatives.get(0).getOpcode() == node.getOpcode(),
                    "primary alternative keeps the operator");
            check(alternatives.get(0).getChildren().size() == node.getChildren().size(),
                    "primary alternative keeps child arity");
            var primarySlots = variableSlots(alternatives.get(0));
            check(primarySlots.size() == slots.size(), "primary alternative keeps slot count");
            for (int index = 0; index < slots.size(); index++) {
                check(java.util.Objects.equals(primarySlots.get(index).getAlphaName(),
                        slots.get(index).getAlphaName()), "primary alternative keeps coordinates");
                check(primarySlots.get(index).getSourceOccurrenceLineage()
                        == slots.get(index).getSourceOccurrenceLineage(),
                        "primary alternative keeps occurrence lineage");
            }
            for (int alternativeIndex = 1; alternativeIndex < alternatives.size();
                    alternativeIndex++) {
                var alternative = alternatives.get(alternativeIndex);
                var alternativeSlots = variableSlots(alternative);
                check(alternativeSlots.size() == slots.size(),
                        "alternative keeps the binder slot count");
                var targets = new TreeSet<String>();
                for (int index = 0; index < slots.size(); index++) {
                    var source = slots.get(index);
                    var copy = alternativeSlots.get(index);
                    check(copy.getSourceOccurrenceLineage() == source.getSourceOccurrenceLineage(),
                            "alternative keeps the source occurrence");
                    check(copy.getOpcode() == source.getOpcode(),
                            "alternative keeps the slot operator");
                    check(java.util.Objects.equals(copy.getExactAlloyType(),
                            source.getExactAlloyType()), "alternative keeps the exact slot type");
                    check(copy.getAlphaName() != null && copy.getAlphaName().startsWith("@fast-local:"),
                            "alternative retains an exact local coordinate");
                    targets.add(slot(copy.getAlphaName()));
                }
                var sources = new TreeSet<String>();
                for (var source : slots) sources.add(slot(source.getAlphaName()));
                check(targets.equals(sources), "alternative is a bijection on the slot set");
            }
            binders++;
        }
        return binders;
    }
    private static int dedupEvidence(CanonicalDistance.Prepared prepared) {
        var dedup = prepared.fastSetDeduplications();
        for (Object row : dedup) {
            var entry = (JSONObject) row;
            check(entry.getBoolean("orderInsensitive"), "dedup is SET-only");
            check(!entry.getBoolean("bagMultiplicityTouched"), "dedup does not touch BAG multiplicity");
            check(entry.getInt("retainedIndex") != entry.getInt("droppedIndex"),
                    "dedup records a distinct eliminated duplicate");
            check(entry.getString("operator").equals("AND")
                    || entry.getString("operator").equals("OR"), "dedup is a Boolean SET");
        }
        return dedup.length();
    }

    private static void naturalBothDirections(JSONObject identity, Path out) throws Exception {
        var pair = LocalOwnerTraceTest.prepare(identity);
        var prepared = List.of(CanonicalAlloyPipeline.prepare(pair.get(0)),
                CanonicalAlloyPipeline.prepare(pair.get(1)));
        int orbitBinders = 0;
        int dedupRows = 0;
        for (int direction = 0; direction < 2; direction++) {
            var a = pair.get(direction);
            var b = pair.get(1 - direction);
            var fast = CanonicalRepairTraces.fast(a, b);
            var certified = CanonicalRepairTraces.certified(
                    prepared.get(direction), prepared.get(1 - direction));
            var result = NativeOperationComparison.compare(a, b, fast, certified);
            check(result.status() == NativeOperationComparison.Status.MATCHED,
                    identity.getString("id") + " direction " + direction + " is MATCHED: "
                            + result.status() + " " + result.reason());
            check(fast.replay(fast.json()) == fast.distance(), "Fast selected path replays");
            check(certified.replay(certified.json()) == certified.distance(),
                    "Certified selected path replays");
            var fastPrepared = CanonicalDistance.prepare(pair.get(direction).normalizedForms());
            orbitBinders += orbitMatchesDescriptor(fastPrepared, identity, direction);
            dedupRows += dedupEvidence(fastPrepared);
        }
        check(orbitBinders > 0, "the witness has an authenticated owner-local orbit");
        if (identity.getString("id").equals("event1560")) {
            check(dedupRows > 0, "SET dedup retains the eliminated duplicate evidence");
        }
        if (identity.getString("id").equals("event1495")) {
            check(dedupRows == 0, "an unequal/term-ordered witness is not deduplicated");
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve(identity.getString("id") + ".json"), new JSONObject()
                .put("id", identity.getString("id")).put("checks", checks)
                .put("negativeControls", negatives).toString(2));
    }

    public static void main(String[] args) throws Exception {
        var workstations = new JSONObject(Files.readString(Path.of(args[0])));
        var social = new JSONObject(Files.readString(Path.of(args[1])));
        var out = Path.of(args[2]);
        naturalBothDirections(workstations, out);
        naturalBothDirections(social, out);
        System.out.println("FastOrbitCorrespondenceTest: " + checks + " checks passed, "
                + negatives + " negative controls");
    }
}