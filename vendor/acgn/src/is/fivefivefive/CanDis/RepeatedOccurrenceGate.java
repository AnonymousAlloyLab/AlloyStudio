package is.fivefivefive.CanDis;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.metric.RepairView.Node;

/**
 * Slot 9 authenticated repeated-occurrence gate.
 *
 * <p>At an order-insensitive parent the structural key alone cannot choose
 * between equal-key siblings. The replacement gate keeps the old refusal when
 * no unused structural candidate exists, keeps index identity for ordered
 * parents, and otherwise refines the unused structural class by an exact
 * authenticated provenance token (the source-occurrence lineage). In a
 * structural class of two or more it admits only the lowest provenance-equal
 * candidate and only when every admitted sibling is a deep twin. Twins are
 * compared on every node field, never on a key hash.
 *
 * <p>Absence is encoded distinctly: a node without an authenticated lineage can
 * only match another absent token, never a present lineage. The gate records
 * the complete admitted class (used or not) so the caller can keep a
 * non-forced choice from being relabelled as a demonstrated mismatch.
 */
final class RepeatedOccurrenceGate {
    private static final Set<String> TOKEN_OPERATORS = Set.of(
            "VARIABLE", "DISJ", "VAR", "DISJVAR", "GENERICRELDECL", "REF");
    private static final int MAX_TWIN_STEPS = 200_000;

    private RepeatedOccurrenceGate() {
    }

    enum Kind { FOUND, AMBIGUOUS, UNSUPPORTED }

    /** Exact presence/lineage pair; absent is distinct from every lineage. */
    record Provenance(boolean present, long lineage) {
        static final Provenance NONE = new Provenance(false, 0L);

        static Provenance of(boolean present, long lineage) {
            return present && lineage > 0L ? new Provenance(true, lineage) : NONE;
        }
    }

    /** The chosen index and the complete admitted class (used or not). */
    record Pick(Kind kind, int index, List<Integer> admitted) {
        static Pick unsupported() {
            return new Pick(Kind.UNSUPPORTED, -1, List.of());
        }

        static Pick ambiguous(List<Integer> admitted) {
            return new Pick(Kind.AMBIGUOUS, -1, List.copyOf(admitted));
        }

        static Pick found(int index, List<Integer> admitted) {
            return new Pick(Kind.FOUND, index, List.copyOf(admitted));
        }

        boolean nonForced() {
            return kind == Kind.FOUND && admitted.size() >= 2;
        }
    }

    static Provenance fastProvenance(EGraphNode node) {
        if (node == null || !TOKEN_OPERATORS.contains(node.getOpcode().name())) {
            return Provenance.NONE;
        }
        return Provenance.of(true, node.getSourceOccurrenceLineage());
    }

    static Provenance certifiedProvenance(Node node) {
        if (node == null || !TOKEN_OPERATORS.contains(node.operator())) {
            return Provenance.NONE;
        }
        EGraphNode source = node.originalSourceOccurrence();
        return Provenance.of(source != null, source == null ? 0L : source.getSourceOccurrenceLineage());
    }

    static Pick pick(
            List<Node> certifiedChildren,
            List<String> structuralKeys,
            List<Provenance> provenanceKeys,
            String structuralKey,
            Provenance provenance,
            Set<Integer> used) {
        Objects.requireNonNull(certifiedChildren, "certifiedChildren");
        Objects.requireNonNull(structuralKeys, "structuralKeys");
        Objects.requireNonNull(provenanceKeys, "provenanceKeys");
        Objects.requireNonNull(structuralKey, "structuralKey");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(used, "used");
        int size = certifiedChildren.size();
        if (structuralKeys.size() != size || provenanceKeys.size() != size) {
            throw new IllegalArgumentException("Occurrence class loses its certified coordinate coverage");
        }
        List<Integer> classMembers = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            if (structuralKey.equals(structuralKeys.get(index))) {
                classMembers.add(index);
            }
        }
        List<Integer> candidates = new ArrayList<>(classMembers.size());
        for (int index : classMembers) {
            if (!used.contains(index)) {
                candidates.add(index);
            }
        }
        if (candidates.isEmpty()) {
            return Pick.unsupported();
        }
        if (classMembers.size() <= 1) {
            return Pick.found(candidates.get(0), List.of());
        }
        List<Integer> admitted = new ArrayList<>(classMembers.size());
        for (int index : classMembers) {
            if (provenance.equals(provenanceKeys.get(index))) {
                admitted.add(index);
            }
        }
        List<Integer> refined = new ArrayList<>(candidates.size());
        for (int index : candidates) {
            if (provenance.equals(provenanceKeys.get(index))) {
                refined.add(index);
            }
        }
        if (refined.isEmpty()) {
            return Pick.ambiguous(admitted);
        }
        int chosen = refined.get(0);
        for (int other : admitted) {
            if (other != chosen && !twin(certifiedChildren.get(chosen), certifiedChildren.get(other))) {
                return Pick.ambiguous(admitted);
            }
        }
        return Pick.found(chosen, admitted);
    }

    /** Deep twin comparison on every node field; key hashes are never equality. */
    static boolean twin(Node left, Node right) {
        return twin(left, right, new int[]{MAX_TWIN_STEPS},
                new IdentityHashMap<>());
    }

    private static boolean twin(
            Node left,
            Node right,
            int[] budget,
            IdentityHashMap<Node, IdentityHashMap<Node, Boolean>> memo) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        IdentityHashMap<Node, Boolean> row = memo.get(left);
        if (row != null && row.containsKey(right)) {
            return row.get(right);
        }
        if (--budget[0] < 0) {
            throw new IllegalArgumentException("TWIN_COMPARISON_BUDGET");
        }
        boolean equal = Objects.equals(left.operator(), right.operator())
                && Objects.equals(left.payload(), right.payload())
                && Objects.equals(left.semanticPayload(), right.semanticPayload())
                && Objects.equals(left.lexicalVariable(), right.lexicalVariable())
                && left.bindingIndex() == right.bindingIndex()
                && left.containerKind() == right.containerKind()
                && left.orderInsensitive() == right.orderInsensitive()
                && Objects.equals(left.observationType(), right.observationType())
                && Objects.equals(left.observationPhases(), right.observationPhases())
                && Objects.equals(left.observationTemporalOperator(), right.observationTemporalOperator())
                && sourceLineage(left) == sourceLineage(right)
                && sameReferenceOrigin(left, right)
                && twinList(left.children(), right.children(), budget, memo)
                && twinList(left.certifiedAlternatives(), right.certifiedAlternatives(), budget, memo);
        if (row == null) {
            row = new IdentityHashMap<>();
            memo.put(left, row);
        }
        row.put(right, equal);
        return equal;
    }

    private static boolean twinList(
            List<Node> left,
            List<Node> right,
            int[] budget,
            IdentityHashMap<Node, IdentityHashMap<Node, Boolean>> memo) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!twin(left.get(index), right.get(index), budget, memo)) {
                return false;
            }
        }
        return true;
    }

    private static long sourceLineage(Node node) {
        EGraphNode source = node.originalSourceOccurrence();
        return source == null ? 0L : source.getSourceOccurrenceLineage();
    }

    private static boolean sameReferenceOrigin(Node left, Node right) {
        var a = left.originalReferenceOrigin();
        var b = right.originalReferenceOrigin();
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.owner() == b.owner()
                && a.authorityId() == b.authorityId()
                && a.parserOccurrenceId() == b.parserOccurrenceId()
                && a.sourceOccurrenceLineage() == b.sourceOccurrenceLineage()
                && a.sourceSlot() == b.sourceSlot();
    }

    static JSONObject classRecord(
            String side,
            String fastParentPath,
            String certifiedParentPath,
            String structuralKey,
            Provenance provenance,
            List<Integer> admitted,
            int chosen) {
        return new JSONObject()
                .put("endpoint", side)
                .put("fastParentPath", fastParentPath)
                .put("certifiedParentPath", certifiedParentPath)
                .put("structuralKey", structuralKey)
                .put("provenancePresent", provenance.present())
                .put("provenanceLineage", provenance.present() ? provenance.lineage() : JSONObject.NULL)
                .put("members", new JSONArray(admitted))
                .put("chosen", chosen);
    }

    /** True when {@code path} is at or below the class member {@code member}. */
    static boolean blocked(JSONArray classes, String endpoint, String path) {
        if (path == null) {
            return false;
        }
        for (Object value : classes) {
            JSONObject row = (JSONObject) value;
            if (!endpoint.equals(row.optString("endpoint"))) {
                continue;
            }
            String parent = row.getString("certifiedParentPath");
            JSONArray members = row.getJSONArray("members");
            for (int index = 0; index < members.length(); index++) {
                String memberPath = parent + "/" + members.getInt(index);
                if (path.equals(memberPath) || path.startsWith(memberPath + "/")) {
                    return true;
                }
            }
        }
        return false;
    }
}
