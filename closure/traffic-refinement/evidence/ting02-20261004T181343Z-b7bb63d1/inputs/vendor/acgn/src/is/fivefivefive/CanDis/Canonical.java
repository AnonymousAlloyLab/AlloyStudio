package is.fivefivefive.CanDis;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.theory.SemanticProfile;

/** Alloy/MASG adapter for the parser-independent canonical-distance core. */
public final class Canonical {
    private Canonical() {
    }

    public static int distance(Multigraph left, Multigraph right) {
        return distance(prepare(left), prepare(right));
    }

    public static int distance(Prepared left, Prepared right) {
        return CanonicalDistance.distance(left.delegate, right.delegate);
    }

    public static CanonicalDistance.DistanceBreakdown distanceBreakdown(
            Prepared left,
            Prepared right) {
        return CanonicalDistance.evaluate(left.delegate, right.delegate);
    }

    public static List<String> edits(Multigraph left, Multigraph right) {
        return edits(prepare(left), prepare(right));
    }

    public static List<String> edits(Prepared left, Prepared right) {
        return CanonicalDistance.edits(left.delegate, right.delegate);
    }

    public static List<String> irTemporalFol(Multigraph graph) {
        return irTemporalFol(prepare(graph));
    }

    public static List<String> irTemporalFol(Prepared prepared) {
        return CanonicalDistance.irTemporalFol(prepared.delegate);
    }

    public static int canonicalFormSize(Multigraph graph) {
        return canonicalFormSize(prepare(graph));
    }

    public static int canonicalFormSize(Prepared prepared) {
        return CanonicalDistance.canonicalFormSize(prepared.delegate);
    }

    public static long eclassCount(Prepared prepared) {
        return prepared.delegate.eclassCount();
    }

    public static long enodeCount(Prepared prepared) {
        return prepared.delegate.enodeCount();
    }

    public static Prepared prepare(Multigraph graph) {
        return prepare(graph, SemanticProfile.alloyOverflowForbidding());
    }

    public static Prepared prepare(Multigraph graph, SemanticProfile semanticProfile) {
        if (graph == null) {
            throw new IllegalArgumentException("Multigraph cannot be null");
        }
        SemanticProfile profile = java.util.Objects.requireNonNull(
                semanticProfile, "semanticProfile");
        IRAgent agent = new IRAgent(graph, profile);
        agent.computeNormalForm();
        List<NormalForm> normalized = Collections.unmodifiableList(
                new ArrayList<>(agent.normalForms()));
        return new Prepared(CanonicalDistance.prepare(normalized), normalized, profile);
    }

    /** Compatibility wrapper that keeps Alloy-specific code out of the core API. */
    public static final class Prepared {
        private final CanonicalDistance.Prepared delegate;
        private final List<NormalForm> normalizedForms;
        private final CallStats callStats;
        private final SemanticProfile semanticProfile;

        private Prepared(
                CanonicalDistance.Prepared delegate,
                List<NormalForm> normalizedForms,
                SemanticProfile semanticProfile) {
            this.delegate = delegate;
            this.normalizedForms = normalizedForms;
            this.callStats = countCalls(normalizedForms);
            this.semanticProfile = semanticProfile;
        }

        List<NormalForm> normalizedForms() {
            return normalizedForms;
        }

        public CallStats callStats() {
            return callStats;
        }

        public SemanticProfile semanticProfile() {
            return semanticProfile;
        }
    }

    public static final class CallStats {
        private final long occurrences;
        private final long containingCalls;

        private CallStats(long occurrences, long containingCalls) {
            this.occurrences = occurrences;
            this.containingCalls = containingCalls;
        }

        public long occurrences() {
            return occurrences;
        }

        public long containingCalls() {
            return containingCalls;
        }
    }

    private static CallStats countCalls(List<NormalForm> normalForms) {
        long occurrences = 0;
        long containingCalls = 0;
        Set<is.fivefivefive.CanDis.core.EGraphNode> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<is.fivefivefive.CanDis.core.EGraphNode> pending = new ArrayDeque<>();
        for (NormalForm normalForm : normalForms) {
            if (normalForm.getMatrixEGraph() != null) {
                pending.add(normalForm.getMatrixEGraph());
            }
        }
        while (!pending.isEmpty()) {
            is.fivefivefive.CanDis.core.EGraphNode node = pending.removeFirst();
            if (!seen.add(node)) {
                continue;
            }
            if (node.getOpcode()
                    == is.fivefivefive.CanDis.core.EGraphNode.Opcode.CALL) {
                is.fivefivefive.CanDis.core.CallMetadata.require(node);
                occurrences++;
                if (containsCallDescendant(node)) {
                    containingCalls++;
                }
            }
            pending.addAll(node.getChildren());
        }
        return new CallStats(occurrences, containingCalls);
    }

    private static boolean containsCallDescendant(
            is.fivefivefive.CanDis.core.EGraphNode root) {
        Set<is.fivefivefive.CanDis.core.EGraphNode> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<is.fivefivefive.CanDis.core.EGraphNode> pending = new ArrayDeque<>();
        pending.addAll(root.getChildren());
        while (!pending.isEmpty()) {
            is.fivefivefive.CanDis.core.EGraphNode node = pending.removeFirst();
            if (!seen.add(node)) {
                continue;
            }
            if (node.getOpcode()
                    == is.fivefivefive.CanDis.core.EGraphNode.Opcode.CALL) {
                return true;
            }
            pending.addAll(node.getChildren());
        }
        return false;
    }
}
