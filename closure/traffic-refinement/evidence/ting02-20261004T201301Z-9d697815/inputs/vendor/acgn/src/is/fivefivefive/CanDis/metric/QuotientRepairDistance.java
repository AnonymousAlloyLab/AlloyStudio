package is.fivefivefive.CanDis.metric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;
import is.fivefivefive.CanDis.metric.RepairView.Binding;
import is.fivefivefive.CanDis.metric.RepairView.BindingRole;
import is.fivefivefive.CanDis.metric.RepairView.Declaration;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.metric.RepairView.Phase;
import is.fivefivefive.CanDis.metric.RepairView.TemporalNode;

/**
 * The established {@code CanonicalDistance} repair geometry evaluated over a
 * certificate-producing {@link RepairView}. Its decomposition and edit units
 * are metric semantics, while Layer 1 supplies admissible scope symmetries.
 * The public evaluator accepts only sealed certified projections and checks
 * in-process producer consistency. It does not grant independent replay
 * authority.
 */
public final class QuotientRepairDistance {
    public static final String VERSION = "certified-fast-rewrite-repair-distance-v12";
    private static final long DEFAULT_MAX_QUANTIFIER_ALIGNMENTS = 1_000_000L;
    private static final long DEFAULT_MAX_SCOPE_ALIGNMENTS = 1_000_000L;
    private static final long DEFAULT_MAX_ALPHA_ALIGNMENTS = 10_000_000L;

    private QuotientRepairDistance() {
    }

    private static final OrderedTreeEditDistance.Adapter<TemporalNode> TEMPORAL_ADAPTER =
            new OrderedTreeEditDistance.Adapter<>() {
                @Override
                public String label(TemporalNode node) {
                    return node.label();
                }

                @Override
                public List<? extends TemporalNode> children(TemporalNode node) {
                    return node.children();
                }
            };

    public static int distance(RepairView left, RepairView right) {
        return evaluate(left, right).distance();
    }

    public static Result evaluate(RepairView left, RepairView right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.semanticProfile().equals(right.semanticProfile())) {
            throw new IllegalArgumentException(
                    "Repair views from different semantic profiles cannot be compared");
        }
        left.requireCertifiedProjection();
        right.requireCertifiedProjection();
        Result candidate = evaluateMetric(
                left,
                right,
                KernelAuthority.CERTIFIED_PROJECTION_PRODUCER_CONSISTENCY);
        boolean sameProducerObservation = left.hasSameProducerObservation(right);
        if (sameProducerObservation != (candidate.distance == 0)) {
            throw new IllegalStateException(sameProducerObservation
                    ? "Equal producer observations have nonzero repair distance: "
                            + "temporal=" + candidate.temporalDistance
                            + ", quantifiers=" + candidate.quantifierDistance
                            + ", matrix=" + candidate.matrixDistance
                    : "A zero repair distance lacks producer-observation equality");
        }
        return candidate;
    }

    static Result evaluateUncheckedForTesting(RepairView left, RepairView right) {
        return evaluateMetric(left, right, KernelAuthority.TEST_ONLY_UNCHECKED);
    }

    static Result evaluateClaimedProducerConsistencyForTesting(
            RepairView left,
            RepairView right) {
        requireComparableProfiles(left, right);
        Result candidate = evaluateMetric(
                left,
                right,
                KernelAuthority.TEST_ONLY_CLAIMED_PRODUCER_CONSISTENCY);
        boolean sameProducerObservation = left.hasSameClaimedProducerObservationForTesting(
                right);
        if (sameProducerObservation != (candidate.distance == 0)) {
            throw new IllegalStateException(sameProducerObservation
                    ? "Equal producer observations have nonzero repair distance"
                    : "A zero repair distance lacks producer-observation equality");
        }
        return candidate;
    }

    private static void requireComparableProfiles(RepairView left, RepairView right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.semanticProfile().equals(right.semanticProfile())) {
            throw new IllegalArgumentException(
                    "Repair views from different semantic profiles cannot be compared");
        }
    }

    private static Result evaluateMetric(
            RepairView left,
            RepairView right,
            KernelAuthority authority) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        MutableStats stats = new MutableStats();
        int temporal = temporalDistance(left.temporalRoot(), right.temporalRoot());
        QuantificationAlignmentSpace quantification =
                QuantificationAlignmentSpace.create(left.phases(), right.phases());
        int quantifiers = quantification.cost;
        int matrix = matrixDistance(
                left.phases(), right.phases(), quantification, stats);
        return new Result(
                checkedTotal(temporal, quantifiers, matrix),
                temporal,
                quantifiers,
                matrix,
                true,
                authority,
                stats.alphaAlignments);
    }

    static int checkedTotal(int temporal, int quantifiers, int matrix) {
        if (temporal < 0 || quantifiers < 0 || matrix < 0) {
            throw new IllegalArgumentException(
                    "Repair-distance components must be non-negative");
        }
        return Math.addExact(temporal, Math.addExact(quantifiers, matrix));
    }

    public enum KernelAuthority {
        /** Both views were minted from matching frozen certified projections. */
        CERTIFIED_PROJECTION_PRODUCER_CONSISTENCY,
        /** Bounded tests only; no zero-kernel claim has been checked. */
        TEST_ONLY_UNCHECKED,
        /** Bounded refinement only; observation identity is fixture-supplied. */
        TEST_ONLY_CLAIMED_PRODUCER_CONSISTENCY
    }

    public static final class Result {
        private final int distance;
        private final int temporalDistance;
        private final int quantifierDistance;
        private final int matrixDistance;
        private final boolean exactForStoredOrbits;
        private final KernelAuthority kernelAuthority;
        private final long binderAlignments;

        private Result(
                int distance,
                int temporalDistance,
                int quantifierDistance,
                int matrixDistance,
                boolean exactForStoredOrbits,
                KernelAuthority kernelAuthority,
                long binderAlignments) {
            this.distance = distance;
            this.temporalDistance = temporalDistance;
            this.quantifierDistance = quantifierDistance;
            this.matrixDistance = matrixDistance;
            this.exactForStoredOrbits = exactForStoredOrbits;
            this.kernelAuthority = Objects.requireNonNull(
                    kernelAuthority, "kernelAuthority");
            this.binderAlignments = binderAlignments;
        }

        public int distance() {
            return distance;
        }

        public int temporalDistance() {
            return temporalDistance;
        }

        public int quantifierDistance() {
            return quantifierDistance;
        }

        public int matrixDistance() {
            return matrixDistance;
        }

        public boolean exactForStoredOrbits() {
            return exactForStoredOrbits;
        }

        public KernelAuthority kernelAuthority() {
            return kernelAuthority;
        }

        public long binderAlignments() {
            return binderAlignments;
        }
    }

    private static int temporalDistance(TemporalNode left, TemporalNode right) {
        return OrderedTreeEditDistance.distance(left, right, TEMPORAL_ADAPTER);
    }

    private static List<IndexedDeclaration> canonicalQuantifierOrder(
            List<IndexedDeclaration> source) {
        List<IndexedDeclaration> result = new ArrayList<>(source);
        for (int start = 0; start < result.size();) {
            String quantifier = result.get(start).declaration.quantifier();
            int end = start + 1;
            if ("ALL".equals(quantifier) || "SOME".equals(quantifier)) {
                while (end < result.size()
                        && quantifier.equals(
                                result.get(end).declaration.quantifier())) {
                    end++;
                }
                result.subList(start, end).sort((left, right) ->
                        compareTuple(left.declaration, right.declaration));
            }
            start = end;
        }
        return result;
    }

    private static int compareTuple(Declaration left, Declaration right) {
        int comparison = left.type().compareTo(right.type());
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.cardinality().compareTo(right.cardinality());
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(left.disjointnessClass(), right.disjointnessClass());
    }

    /**
     * Minimum declaration edits and their binding correspondences. Matrix
     * alignment may use a paid tuple modification or an explicit positional
     * parameter diagonal selected by the edit plan, but never a coincident
     * coordinate invented after that plan has been chosen.
     */
    private static final class QuantificationAlignmentSpace {
        private final int cost;
        private final List<QuantifierEditComponent> components;

        private QuantificationAlignmentSpace(
                int cost,
                List<QuantifierEditComponent> components) {
            this.cost = cost;
            this.components = List.copyOf(components);
        }

        private static QuantificationAlignmentSpace create(
                List<Phase> left,
                List<Phase> right) {
            List<QuantifierEditComponent> components = new ArrayList<>();
            int cost = 0;

            QuantifierEditComponent parameters = QuantifierEditComponent.create(
                    parameterDeclarations(left), parameterDeclarations(right), false);
            components.add(parameters);
            cost = Math.addExact(cost, parameters.cost());

            int aligned = Math.min(left.size(), right.size());
            for (int phase = 0; phase < aligned; phase++) {
                QuantifierEditComponent component = QuantifierEditComponent.create(
                        quantifiedDeclarations(left.get(phase), phase),
                        quantifiedDeclarations(right.get(phase), phase),
                        true);
                components.add(component);
                cost = Math.addExact(cost, component.cost());
            }
            for (int phase = aligned; phase < left.size(); phase++) {
                cost = Math.addExact(cost, left.get(phase).quantifiers().size());
            }
            for (int phase = aligned; phase < right.size(); phase++) {
                cost = Math.addExact(cost, right.get(phase).quantifiers().size());
            }
            return new QuantificationAlignmentSpace(cost, components);
        }

        private void forEachEditAlignment(
                GlobalBindingIndex left,
                GlobalBindingIndex right,
                Consumer<Map<Integer, Integer>> consumer,
                MutableStats stats) {
            enumerateComponents(
                    left,
                    right,
                    consumer,
                    stats,
                    0,
                    new LinkedHashMap<>());
        }

        private void enumerateComponents(
                GlobalBindingIndex left,
                GlobalBindingIndex right,
                Consumer<Map<Integer, Integer>> consumer,
                MutableStats stats,
                int componentIndex,
                Map<Integer, Integer> correspondence) {
            if (componentIndex == components.size()) {
                stats.consumeQuantifierAlignment();
                consumer.accept(Map.copyOf(correspondence));
                return;
            }
            components.get(componentIndex).forEachMinimumAlignment(
                    pairs -> {
                List<Integer> insertedLeft = new ArrayList<>();
                List<Integer> insertedRight = new ArrayList<>();
                for (EditCorrespondence pair : pairs) {
                    Integer leftIndex = left.indices.get(pair.left.identity);
                    Integer rightIndex = right.indices.get(pair.right.identity);
                    if (leftIndex == null || rightIndex == null) {
                        throw new IllegalStateException(
                                "A minimum quantifier correspondence lacks a projected binding");
                    }
                    boolean inserted = addInjectiveCorrespondence(
                            correspondence, leftIndex, rightIndex);
                    if (inserted) {
                        insertedLeft.add(leftIndex);
                        insertedRight.add(rightIndex);
                    }
                }
                enumerateComponents(
                        left,
                        right,
                        consumer,
                        stats,
                        componentIndex + 1,
                        correspondence);
                for (int index = insertedLeft.size() - 1; index >= 0; index--) {
                    Integer removed = correspondence.remove(insertedLeft.get(index));
                    if (removed == null
                            || removed.intValue()
                                    != insertedRight.get(index).intValue()) {
                        throw new IllegalStateException(
                                "Quantifier correspondence backtracking lost identity");
                    }
                }
            });
        }

        private static List<IndexedDeclaration> parameterDeclarations(
                List<Phase> phases) {
            if (phases.isEmpty()) {
                return List.of();
            }
            List<IndexedDeclaration> result = new ArrayList<>();
            for (Binding binding : phases.get(0).bindings()) {
                if (binding.role() == BindingRole.PARAMETER) {
                    result.add(new IndexedDeclaration(
                            binding.declaration(),
                            GlobalBindingIdentity.from(binding),
                            binding.ordinal()));
                }
            }
            result.sort((left, right) -> Integer.compare(left.ordinal, right.ordinal));
            return List.copyOf(result);
        }

        private static List<IndexedDeclaration> quantifiedDeclarations(
                Phase phase,
                int phaseIndex) {
            List<IndexedDeclaration> result = new ArrayList<>();
            for (int ordinal = 0; ordinal < phase.quantifiers().size(); ordinal++) {
                Binding binding = matrixBinding(phase, phaseIndex, ordinal);
                Declaration declaration = phase.quantifiers().get(ordinal);
                if (binding.declaration() != declaration
                        && !binding.declaration().sameCertifiedPayload(declaration)) {
                    throw new IllegalStateException(
                            "Quantifier and projected binding declarations disagree");
                }
                result.add(new IndexedDeclaration(
                        declaration,
                        GlobalBindingIdentity.from(binding),
                        ordinal));
            }
            return List.copyOf(result);
        }

        private static Binding matrixBinding(
                Phase phase,
                int phaseIndex,
                int ordinal) {
            for (Binding binding : phase.bindings()) {
                if (binding.role() == BindingRole.MATRIX
                        && binding.ownerPhase() == phaseIndex
                        && binding.ordinal() == ordinal) {
                    return binding;
                }
            }
            throw new IllegalStateException(
                    "Quantifier " + ordinal + " in phase " + phaseIndex
                            + " lacks its certified matrix binding");
        }
    }

    static boolean addInjectiveCorrespondence(
            Map<Integer, Integer> correspondence,
            int leftIndex,
            int rightIndex) {
        Objects.requireNonNull(correspondence, "correspondence");
        for (Map.Entry<Integer, Integer> existing : correspondence.entrySet()) {
            if (existing.getValue().intValue() == rightIndex
                    && existing.getKey().intValue() != leftIndex) {
                throw new IllegalStateException(
                        "Two left bindings share one quantifier correspondence");
            }
        }
        Integer priorRight = correspondence.putIfAbsent(leftIndex, rightIndex);
        if (priorRight != null && priorRight.intValue() != rightIndex) {
            throw new IllegalStateException(
                    "One left binding has two quantifier correspondences");
        }
        return priorRight == null;
    }

    private static final class QuantifierEditComponent {
        private final List<IndexedDeclaration> left;
        private final List<IndexedDeclaration> right;
        private final int[][] distance;

        private QuantifierEditComponent(
                List<IndexedDeclaration> left,
                List<IndexedDeclaration> right,
                int[][] distance) {
            this.left = left;
            this.right = right;
            this.distance = distance;
        }

        private static QuantifierEditComponent create(
                List<IndexedDeclaration> leftSource,
                List<IndexedDeclaration> rightSource,
                boolean canonicalizeExchangeRuns) {
            List<IndexedDeclaration> left = canonicalizeExchangeRuns
                    ? canonicalQuantifierOrder(leftSource)
                    : List.copyOf(leftSource);
            List<IndexedDeclaration> right = canonicalizeExchangeRuns
                    ? canonicalQuantifierOrder(rightSource)
                    : List.copyOf(rightSource);
            int[][] distance = new int[Math.addExact(left.size(), 1)]
                    [Math.addExact(right.size(), 1)];
            for (int row = 1; row <= left.size(); row++) {
                distance[row][0] = Math.addExact(distance[row - 1][0], 1);
            }
            for (int column = 1; column <= right.size(); column++) {
                distance[0][column] = Math.addExact(distance[0][column - 1], 1);
            }
            for (int row = 1; row <= left.size(); row++) {
                for (int column = 1; column <= right.size(); column++) {
                    int update = Math.addExact(
                            distance[row - 1][column - 1],
                            tupleUpdateCost(
                                    left.get(row - 1), right.get(column - 1)));
                    int delete = Math.addExact(distance[row - 1][column], 1);
                    int insert = Math.addExact(distance[row][column - 1], 1);
                    distance[row][column] = Math.min(
                            update, Math.min(delete, insert));
                }
            }
            return new QuantifierEditComponent(left, right, distance);
        }

        private int cost() {
            return distance[left.size()][right.size()];
        }

        private void forEachMinimumAlignment(
                Consumer<List<EditCorrespondence>> consumer) {
            enumerateMinimumAlignments(
                    left.size(),
                    right.size(),
                    new ArrayList<>(),
                    consumer);
        }

        private void enumerateMinimumAlignments(
                int row,
                int column,
                List<EditCorrespondence> reversed,
                Consumer<List<EditCorrespondence>> consumer) {
            if (row == 0 && column == 0) {
                List<EditCorrespondence> result = new ArrayList<>(reversed);
                java.util.Collections.reverse(result);
                consumer.accept(List.copyOf(result));
                return;
            }
            if (row > 0 && column > 0) {
                IndexedDeclaration leftValue = left.get(row - 1);
                IndexedDeclaration rightValue = right.get(column - 1);
                int updateCost = tupleUpdateCost(leftValue, rightValue);
                if (distance[row][column] == Math.addExact(
                        distance[row - 1][column - 1], updateCost)) {
                    boolean carriesBinding = updateCost != 0
                            || leftValue.identity.parameter;
                    if (carriesBinding) {
                        reversed.add(new EditCorrespondence(
                                leftValue, rightValue));
                    }
                    enumerateMinimumAlignments(
                            row - 1, column - 1, reversed, consumer);
                    if (carriesBinding) {
                        reversed.remove(reversed.size() - 1);
                    }
                }
            }
            if (row > 0
                    && distance[row][column]
                            == Math.addExact(distance[row - 1][column], 1)) {
                enumerateMinimumAlignments(
                        row - 1, column, reversed, consumer);
            }
            if (column > 0
                    && distance[row][column]
                            == Math.addExact(distance[row][column - 1], 1)) {
                enumerateMinimumAlignments(
                        row, column - 1, reversed, consumer);
            }
        }

        private static int tupleUpdateCost(
                IndexedDeclaration left,
                IndexedDeclaration right) {
            return left.declaration.sameRepairTuple(right.declaration) ? 0 : 1;
        }
    }

    private static final class IndexedDeclaration {
        private final Declaration declaration;
        private final GlobalBindingIdentity identity;
        private final int ordinal;

        private IndexedDeclaration(
                Declaration declaration,
                GlobalBindingIdentity identity,
                int ordinal) {
            this.declaration = Objects.requireNonNull(declaration, "declaration");
            this.identity = Objects.requireNonNull(identity, "binding identity");
            this.ordinal = ordinal;
        }
    }

    private static final class EditCorrespondence {
        private final IndexedDeclaration left;
        private final IndexedDeclaration right;

        private EditCorrespondence(
                IndexedDeclaration left,
                IndexedDeclaration right) {
            this.left = left;
            this.right = right;
        }
    }

    private static int matrixDistance(
            List<Phase> left,
            List<Phase> right,
            QuantificationAlignmentSpace quantification,
            MutableStats stats) {
        // One owner-coordinate mapping must govern its matrix and every temporal heir.
        int aligned = Math.min(left.size(), right.size());
        int result = 0;
        for (int index = aligned; index < left.size(); index++) {
            result = Math.addExact(result, nodeSize(left.get(index).matrix()));
        }
        for (int index = aligned; index < right.size(); index++) {
            result = Math.addExact(result, nodeSize(right.get(index).matrix()));
        }
        GlobalBindingIndex leftBindings = GlobalBindingIndex.create(left, aligned);
        GlobalBindingIndex rightBindings = GlobalBindingIndex.create(right, aligned);
        if (aligned == 0) {
            return result;
        }

        int fixedUnalignedCost = result;
        int[] best = {Integer.MAX_VALUE};
        quantification.forEachEditAlignment(
                leftBindings, rightBindings, correspondence -> {
            int candidate = matrixDistanceForAlignment(
                    leftBindings, rightBindings, correspondence, stats);
            best[0] = Math.min(best[0], candidate);
        }, stats);
        if (best[0] == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "No minimum-cost quantifier alignment was evaluated");
        }
        return Math.addExact(fixedUnalignedCost, best[0]);
    }

    private static int matrixDistanceForAlignment(
            GlobalBindingIndex leftBindings,
            GlobalBindingIndex rightBindings,
            Map<Integer, Integer> editCorrespondence,
            MutableStats stats) {
        int maximumMatches = -1;
        int best = Integer.MAX_VALUE;
        int[] mapping = new int[leftBindings.bindings.size()];
        Arrays.fill(mapping, -1);
        boolean[] usedRight = new boolean[rightBindings.bindings.size()];
        boolean[] orbitRelevant = new boolean[leftBindings.bindings.size()];
        for (Map.Entry<Integer, Integer> entry : editCorrespondence.entrySet()) {
            int leftIndex = entry.getKey();
            int rightIndex = entry.getValue();
            if (mapping[leftIndex] >= 0 || usedRight[rightIndex]) {
                throw new IllegalStateException(
                        "A quantifier edit correspondence is not one-to-one");
            }
            mapping[leftIndex] = rightIndex;
            usedRight[rightIndex] = true;
        }

        int[] maximum = {-1};
        int[] minimum = {Integer.MAX_VALUE};
        forEachGlobalScopeAlignment(leftBindings, rightBindings, scope -> {
            stats.consumeScopeAlignment();
            List<Integer> leftOrder = new ArrayList<>(leftBindings.used);
            leftOrder.removeIf(index -> mapping[index] >= 0);
            leftOrder.sort((first, second) -> Integer.compare(
                    globalCandidateCount(
                            leftBindings.binding(first), rightBindings, scope, usedRight),
                    globalCandidateCount(
                            leftBindings.binding(second), rightBindings, scope, usedRight)));
            int requiredMatches = maximumGlobalCompatibleMatches(
                    leftBindings, rightBindings, leftOrder, scope, usedRight);
            if (requiredMatches < maximum[0]) {
                return;
            }
            if (requiredMatches > maximum[0]) {
                maximum[0] = requiredMatches;
                minimum[0] = Integer.MAX_VALUE;
            }
            minimum[0] = searchGlobalMappings(
                    leftBindings,
                    rightBindings,
                    leftOrder,
                    scope,
                    mapping,
                    usedRight,
                    orbitRelevant,
                    0,
                    0,
                    requiredMatches,
                    minimum[0],
                    stats);
        });
        maximumMatches = maximum[0];
        best = minimum[0];
        if (maximumMatches < 0 || best == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "No certified global alpha alignment was evaluated");
        }
        return best;
    }

    private static int searchGlobalMappings(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            List<Integer> leftOrder,
            ScopeAlignment scope,
            int[] mapping,
            boolean[] usedRight,
            boolean[] orbitRelevant,
            int index,
            int matched,
            int requiredMatches,
            int best,
            MutableStats stats) {
        if (index == leftOrder.size()) {
            if (matched != requiredMatches) {
                return best;
            }
            stats.consumeAlphaAlignment();
            return Math.min(best, globallyMappedMatrixDistance(left, right, mapping));
        }
        int leftIndex = leftOrder.get(index);
        GlobalBinding leftBinding = left.binding(leftIndex);
        boolean mapped = false;
        for (int rightIndex : right.used) {
            if (usedRight[rightIndex]
                    || !globalCompatible(
                            leftBinding, right.binding(rightIndex), scope)
                    || !preservesCertifiedOrbitRelations(
                            left,
                            right,
                            leftIndex,
                            rightIndex,
                            mapping,
                            orbitRelevant)) {
                continue;
            }
            mapped = true;
            mapping[leftIndex] = rightIndex;
            usedRight[rightIndex] = true;
            orbitRelevant[leftIndex] = true;
            best = searchGlobalMappings(
                    left,
                    right,
                    leftOrder,
                    scope,
                    mapping,
                    usedRight,
                    orbitRelevant,
                    index + 1,
                    matched + 1,
                    requiredMatches,
                    best,
                    stats);
            orbitRelevant[leftIndex] = false;
            usedRight[rightIndex] = false;
            mapping[leftIndex] = -1;
            if (best == 0) {
                return 0;
            }
        }
        if (!mapped || matched + leftOrder.size() - index - 1 >= requiredMatches) {
            best = searchGlobalMappings(
                    left,
                    right,
                    leftOrder,
                    scope,
                    mapping,
                    usedRight,
                    orbitRelevant,
                    index + 1,
                    matched,
                    requiredMatches,
                    best,
                    stats);
        }
        return best;
    }

    private static boolean preservesCertifiedOrbitRelations(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            int candidateLeft,
            int candidateRight,
            int[] mapping,
            boolean[] orbitRelevant) {
        for (int priorLeft = 0; priorLeft < mapping.length; priorLeft++) {
            if (!orbitRelevant[priorLeft]) {
                continue;
            }
            int priorRight = mapping[priorLeft];
            if (priorRight < 0
                    || sameCertifiedOrbit(
                            left.binding(candidateLeft), left.binding(priorLeft))
                            != sameCertifiedOrbit(
                                    right.binding(candidateRight),
                                    right.binding(priorRight))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameCertifiedOrbit(
            GlobalBinding first,
            GlobalBinding second) {
        return !first.identity.parameter
                && !second.identity.parameter
                && first.identity.ownerPhase == second.identity.ownerPhase
                && first.identity.ownerContext.equals(second.identity.ownerContext)
                && first.binding.certifiedOrbit().contains(
                        second.identity.coordinate)
                && second.binding.certifiedOrbit().contains(
                        first.identity.coordinate);
    }

    private static int globallyMappedMatrixDistance(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            int[] globalMapping) {
        int result = 0;
        for (int phaseIndex = 0; phaseIndex < left.phaseCount; phaseIndex++) {
            Phase leftPhase = left.phases.get(phaseIndex);
            Phase rightPhase = right.phases.get(phaseIndex);
            int[] localMapping = new int[leftPhase.bindings().size()];
            Arrays.fill(localMapping, -1);
            int[] localToGlobal = left.localToGlobal.get(phaseIndex);
            for (int local = 0; local < localToGlobal.length; local++) {
                int leftGlobal = localToGlobal[local];
                int rightGlobal = globalMapping[leftGlobal];
                if (rightGlobal >= 0) {
                    Integer rightLocal = right.usedLocalByGlobal
                            .get(phaseIndex).get(rightGlobal);
                    if (rightLocal != null) {
                        localMapping[local] = rightLocal;
                    }
                }
            }
            result = Math.addExact(
                    result,
                    new MatrixDistanceContext(localMapping).distance(
                            leftPhase.matrix(), rightPhase.matrix()));
        }
        return result;
    }

    private static int globalCandidateCount(
            GlobalBinding left,
            GlobalBindingIndex right,
            ScopeAlignment scope,
            boolean[] unavailableRight) {
        int result = 0;
        for (int rightIndex : right.used) {
            if (!unavailableRight[rightIndex]
                    && globalCompatible(left, right.binding(rightIndex), scope)) {
                result++;
            }
        }
        return result;
    }

    private static int maximumGlobalCompatibleMatches(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            List<Integer> leftOrder,
            ScopeAlignment scope,
            boolean[] unavailableRight) {
        int[] matchedLeftByRight = new int[right.bindings.size()];
        Arrays.fill(matchedLeftByRight, -1);
        int result = 0;
        for (int leftIndex : leftOrder) {
            if (augmentGlobalMatching(
                    leftIndex,
                    left,
                    right,
                    scope,
                    matchedLeftByRight,
                    new boolean[right.bindings.size()],
                    unavailableRight)) {
                result++;
            }
        }
        return result;
    }

    private static boolean augmentGlobalMatching(
            int leftIndex,
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            ScopeAlignment scope,
            int[] matchedLeftByRight,
            boolean[] visitedRight,
            boolean[] unavailableRight) {
        for (int rightIndex : right.used) {
            if (unavailableRight[rightIndex]
                    || visitedRight[rightIndex]
                    || !globalCompatible(
                            left.binding(leftIndex), right.binding(rightIndex), scope)) {
                continue;
            }
            visitedRight[rightIndex] = true;
            if (matchedLeftByRight[rightIndex] < 0
                    || augmentGlobalMatching(
                            matchedLeftByRight[rightIndex],
                            left,
                            right,
                            scope,
                            matchedLeftByRight,
                            visitedRight,
                            unavailableRight)) {
                matchedLeftByRight[rightIndex] = leftIndex;
                return true;
            }
        }
        return false;
    }

    private static boolean globalCompatible(
            GlobalBinding left,
            GlobalBinding right,
            ScopeAlignment scope) {
        if (!globalBaseCompatible(left, right)) {
            return false;
        }
        if (left.identity.parameter) {
            return left.identity.coordinate == right.identity.coordinate;
        }
        ScopeOwner owner = globalScopeOwner(left);
        ScopeCoordinate target = scope.mappings.get(new ScopeCoordinate(
                owner, left.binding.declaration().exchangeClass()));
        return target != null && target.equals(new ScopeCoordinate(
                globalScopeOwner(right), right.binding.declaration().exchangeClass()));
    }

    private static boolean globalBaseCompatible(
            GlobalBinding left,
            GlobalBinding right) {
        if (left.identity.parameter != right.identity.parameter
                || !left.binding.declaration().sameCertifiedPayload(
                        right.binding.declaration())) {
            return false;
        }
        if (left.identity.parameter) {
            return true;
        }
        if (left.identity.ownerPhase != right.identity.ownerPhase) {
            return false;
        }
        if (!left.identity.ownerContext.equals(right.identity.ownerContext)) {
            return false;
        }
        // A producer-certified prenex coordinate supersedes its presentation
        // path. Without that explicit authority, lexical scope remains part
        // of admissibility and must match exactly.
        return left.binding.prenexPathErasureCertified()
                        && right.binding.prenexPathErasureCertified()
                || left.binding.bindingPath().equals(right.binding.bindingPath());
    }

    private static void forEachGlobalScopeAlignment(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            Consumer<ScopeAlignment> consumer) {
        Map<ScopeOwner, Map<Integer, List<Integer>>> leftBlocks =
                globalScopeBlocks(left);
        Map<ScopeOwner, Map<Integer, List<Integer>>> rightBlocks =
                globalScopeBlocks(right);
        List<ScopeOwner> owners = new ArrayList<>(leftBlocks.keySet());
        owners.sort(ScopeOwner::compareTo);
        forEachGlobalScopeAlignment(
                left,
                right,
                leftBlocks,
                rightBlocks,
                owners,
                0,
                ScopeAlignment.EMPTY,
                consumer);
    }

    private static void forEachGlobalScopeAlignment(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            Map<ScopeOwner, Map<Integer, List<Integer>>> leftBlocks,
            Map<ScopeOwner, Map<Integer, List<Integer>>> rightBlocks,
            List<ScopeOwner> owners,
            int ownerIndex,
            ScopeAlignment prefix,
            Consumer<ScopeAlignment> consumer) {
        if (ownerIndex == owners.size()) {
            consumer.accept(prefix);
            return;
        }
        ScopeOwner owner = owners.get(ownerIndex);
        forEachGlobalBlockMapping(
                left,
                right,
                leftBlocks.get(owner),
                rightBlocks.getOrDefault(owner, java.util.Collections.emptyMap()),
                mapping -> forEachGlobalScopeAlignment(
                        left,
                        right,
                        leftBlocks,
                        rightBlocks,
                        owners,
                        ownerIndex + 1,
                        prefix.extend(owner, mapping),
                        consumer));
    }

    private static Map<ScopeOwner, Map<Integer, List<Integer>>> globalScopeBlocks(
            GlobalBindingIndex bindings) {
        Map<ScopeOwner, Map<Integer, List<Integer>>> result = new LinkedHashMap<>();
        for (int index : bindings.used) {
            GlobalBinding binding = bindings.binding(index);
            if (binding.identity.parameter) {
                continue;
            }
            result.computeIfAbsent(
                            globalScopeOwner(binding), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(
                            binding.binding.declaration().exchangeClass(),
                            ignored -> new ArrayList<>())
                    .add(index);
        }
        return result;
    }

    private static ScopeOwner globalScopeOwner(GlobalBinding binding) {
        BindingRole role = binding.binding.role() == BindingRole.LOCAL_INHERITED
                ? BindingRole.LOCAL_INHERITED : BindingRole.MATRIX;
        return new ScopeOwner(
                role,
                binding.identity.ownerPhase,
                binding.identity.ownerContext);
    }

    private static void forEachGlobalBlockMapping(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            Map<Integer, List<Integer>> leftBlocks,
            Map<Integer, List<Integer>> rightBlocks,
            Consumer<Map<Integer, Integer>> consumer) {
        List<Integer> leftClasses = new ArrayList<>(leftBlocks.keySet());
        List<Integer> rightClasses = new ArrayList<>(rightBlocks.keySet());
        leftClasses.sort(Integer::compareTo);
        rightClasses.sort(Integer::compareTo);
        enumerateGlobalBlockMappings(
                left,
                right,
                leftBlocks,
                rightBlocks,
                leftClasses,
                rightClasses,
                0,
                0,
                new LinkedHashMap<>(),
                consumer);
    }

    private static void enumerateGlobalBlockMappings(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            Map<Integer, List<Integer>> leftBlocks,
            Map<Integer, List<Integer>> rightBlocks,
            List<Integer> leftClasses,
            List<Integer> rightClasses,
            int leftPosition,
            int minimumRightPosition,
            Map<Integer, Integer> current,
            Consumer<Map<Integer, Integer>> consumer) {
        if (leftPosition == leftClasses.size()) {
            consumer.accept(Map.copyOf(current));
            return;
        }
        int leftClass = leftClasses.get(leftPosition);
        enumerateGlobalBlockMappings(
                left,
                right,
                leftBlocks,
                rightBlocks,
                leftClasses,
                rightClasses,
                leftPosition + 1,
                minimumRightPosition,
                current,
                consumer);
        for (int rightPosition = minimumRightPosition;
                rightPosition < rightClasses.size(); rightPosition++) {
            int rightClass = rightClasses.get(rightPosition);
            if (!globalBlocksCompatible(
                    left,
                    right,
                    leftBlocks.get(leftClass),
                    rightBlocks.get(rightClass))) {
                continue;
            }
            current.put(leftClass, rightClass);
            enumerateGlobalBlockMappings(
                    left,
                    right,
                    leftBlocks,
                    rightBlocks,
                    leftClasses,
                    rightClasses,
                    leftPosition + 1,
                    rightPosition + 1,
                    current,
                    consumer);
            current.remove(leftClass);
        }
    }

    private static boolean globalBlocksCompatible(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            List<Integer> leftBlock,
            List<Integer> rightBlock) {
        for (int leftIndex : leftBlock) {
            for (int rightIndex : rightBlock) {
                if (globalBaseCompatible(
                        left.binding(leftIndex), right.binding(rightIndex))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<Integer> usedBindings(Node root) {
        Set<Integer> result = new HashSet<>();
        collectUsedBindings(root, result);
        return result;
    }

    private static void collectUsedBindings(Node node, Set<Integer> output) {
        if (node == null) {
            return;
        }
        if (node.isVariable() && node.bindingIndex() >= 0) {
            output.add(node.bindingIndex());
        }
        for (Node child : node.children()) {
            collectUsedBindings(child, output);
        }
        for (Node alternative : node.certifiedAlternatives()) {
            collectUsedBindings(alternative, output);
        }
    }

    private static int nodeSize(Node node) {
        return node == null ? 0 : node.minimumRepresentativeSize();
    }

    private static final class MatrixDistanceContext {
        private final int[] mapping;
        private final Map<NodePair, Integer> memo = new HashMap<>();

        private MatrixDistanceContext(int[] mapping) {
            this.mapping = mapping.clone();
        }

        private int distance(Node left, Node right) {
            if (left == null) {
                return nodeSize(right);
            }
            if (right == null) {
                return nodeSize(left);
            }
            NodePair key = new NodePair(left, right);
            Integer remembered = memo.get(key);
            if (remembered != null) {
                return remembered;
            }
            if (!left.certifiedAlternatives().isEmpty()
                    || !right.certifiedAlternatives().isEmpty()) {
                List<Node> leftAlternatives = left.certifiedAlternatives().isEmpty()
                        ? List.of(left)
                        : left.certifiedAlternatives();
                List<Node> rightAlternatives = right.certifiedAlternatives().isEmpty()
                        ? List.of(right)
                        : right.certifiedAlternatives();
                int best = Integer.MAX_VALUE;
                for (Node leftAlternative : leftAlternatives) {
                    for (Node rightAlternative : rightAlternatives) {
                        best = Math.min(
                                best, distance(leftAlternative, rightAlternative));
                        if (best == 0) {
                            memo.put(key, 0);
                            return 0;
                        }
                    }
                }
                memo.put(key, best);
                return best;
            }
            int children = left.operator().equals(right.operator())
                    && left.orderInsensitive()
                    && right.orderInsensitive()
                    ? unorderedDistance(left.children(), right.children())
                    : sequenceDistance(left.children(), right.children());
            int result = Math.addExact(updateCost(left, right), children);
            memo.put(key, result);
            return result;
        }

        private int updateCost(Node left, Node right) {
            if (!left.operator().equals(right.operator())) {
                return 1;
            }
            if (left.isVariable()) {
                boolean leftBound = left.bindingIndex() >= 0;
                boolean rightBound = right.bindingIndex() >= 0;
                if (leftBound != rightBound) {
                    return 1;
                }
                if (leftBound) {
                    int mapped = mapping[left.bindingIndex()];
                    return mapped >= 0 && mapped == right.bindingIndex() ? 0 : 1;
                }
                return Objects.equals(
                        left.lexicalVariable(), right.lexicalVariable()) ? 0 : 1;
            }
            return Objects.equals(
                    left.semanticPayload(), right.semanticPayload()) ? 0 : 1;
        }

        private int sequenceDistance(List<Node> left, List<Node> right) {
            if (left.size() == 1 && right.size() == 1) {
                return distance(left.get(0), right.get(0));
            }
            int[] previous = new int[Math.addExact(right.size(), 1)];
            int[] current = new int[Math.addExact(right.size(), 1)];
            for (int j = 1; j <= right.size(); j++) {
                previous[j] = Math.addExact(
                        previous[j - 1], right.get(j - 1).size());
            }
            for (int i = 1; i <= left.size(); i++) {
                current[0] = Math.addExact(
                        previous[0], left.get(i - 1).size());
                for (int j = 1; j <= right.size(); j++) {
                    int delete = Math.addExact(
                            previous[j], left.get(i - 1).size());
                    int insert = Math.addExact(
                            current[j - 1], right.get(j - 1).size());
                    int update = Math.addExact(
                            previous[j - 1],
                            distance(left.get(i - 1), right.get(j - 1)));
                    current[j] = Math.min(update, Math.min(delete, insert));
                }
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            return previous[right.size()];
        }

        private int unorderedDistance(List<Node> left, List<Node> right) {
            if (left.isEmpty() || right.isEmpty()) {
                return sequenceDistance(left, right);
            }
            int dimension = Math.addExact(left.size(), right.size());
            int[][] costs = new int[dimension][dimension];
            for (int i = 0; i < dimension; i++) {
                for (int j = 0; j < dimension; j++) {
                    if (i < left.size() && j < right.size()) {
                        costs[i][j] = distance(left.get(i), right.get(j));
                    } else if (i < left.size()) {
                        costs[i][j] = left.get(i).size();
                    } else if (j < right.size()) {
                        costs[i][j] = right.get(j).size();
                    }
                }
            }
            return minimumAssignmentCost(costs);
        }
    }

    static int minimumAssignmentCost(int[][] costs) {
        Objects.requireNonNull(costs, "costs");
        int size = costs.length;
        for (int row = 0; row < size; row++) {
            if (costs[row] == null || costs[row].length != size) {
                throw new IllegalArgumentException(
                        "Assignment cost matrix must be square");
            }
            for (int cost : costs[row]) {
                if (cost < 0) {
                    throw new IllegalArgumentException(
                            "Assignment costs must be non-negative");
                }
            }
        }
        long[] rowPotential = new long[Math.addExact(size, 1)];
        long[] columnPotential = new long[Math.addExact(size, 1)];
        int[] columnMatch = new int[Math.addExact(size, 1)];
        int[] predecessor = new int[Math.addExact(size, 1)];
        for (int row = 1; row <= size; row++) {
            columnMatch[0] = row;
            int column = 0;
            long[] minimum = new long[size + 1];
            Arrays.fill(minimum, Long.MAX_VALUE);
            boolean[] used = new boolean[size + 1];
            do {
                used[column] = true;
                int matchedRow = columnMatch[column];
                long delta = Long.MAX_VALUE;
                int nextColumn = 0;
                for (int candidate = 1; candidate <= size; candidate++) {
                    if (used[candidate]) {
                        continue;
                    }
                    long reduced = Math.subtractExact(
                            Math.subtractExact(
                                    (long) costs[matchedRow - 1][candidate - 1],
                                    rowPotential[matchedRow]),
                            columnPotential[candidate]);
                    if (reduced < minimum[candidate]) {
                        minimum[candidate] = reduced;
                        predecessor[candidate] = column;
                    }
                    if (minimum[candidate] < delta) {
                        delta = minimum[candidate];
                        nextColumn = candidate;
                    }
                }
                for (int candidate = 0; candidate <= size; candidate++) {
                    if (used[candidate]) {
                        rowPotential[columnMatch[candidate]] = Math.addExact(
                                rowPotential[columnMatch[candidate]], delta);
                        columnPotential[candidate] = Math.subtractExact(
                                columnPotential[candidate], delta);
                    } else if (minimum[candidate] != Long.MAX_VALUE) {
                        minimum[candidate] = Math.subtractExact(
                                minimum[candidate], delta);
                    }
                }
                column = nextColumn;
            } while (columnMatch[column] != 0);
            do {
                int previous = predecessor[column];
                columnMatch[column] = columnMatch[previous];
                column = previous;
            } while (column != 0);
        }
        return Math.toIntExact(Math.negateExact(columnPotential[0]));
    }

    private static final class ScopeOwner implements Comparable<ScopeOwner> {
        private final BindingRole role;
        private final int phase;
        private final String context;

        private ScopeOwner(BindingRole role, int phase) {
            this(role, phase, "");
        }

        private ScopeOwner(BindingRole role, int phase, String context) {
            this.role = role;
            this.phase = phase;
            this.context = context == null ? "" : context;
        }

        @Override
        public int compareTo(ScopeOwner other) {
            int comparison = role.compareTo(other.role);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(phase, other.phase);
            return comparison != 0 ? comparison : context.compareTo(other.context);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ScopeOwner
                    && role == ((ScopeOwner) other).role
                    && phase == ((ScopeOwner) other).phase
                    && context.equals(((ScopeOwner) other).context);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * role.hashCode() + phase) + context.hashCode();
        }
    }

    private static final class ScopeCoordinate {
        private final ScopeOwner owner;
        private final int exchangeClass;

        private ScopeCoordinate(ScopeOwner owner, int exchangeClass) {
            this.owner = owner;
            this.exchangeClass = exchangeClass;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ScopeCoordinate
                    && owner.equals(((ScopeCoordinate) other).owner)
                    && exchangeClass == ((ScopeCoordinate) other).exchangeClass;
        }

        @Override
        public int hashCode() {
            return 31 * owner.hashCode() + exchangeClass;
        }
    }

    private static final class ScopeAlignment {
        private static final ScopeAlignment EMPTY = new ScopeAlignment(
                java.util.Collections.emptyMap());

        private final Map<ScopeCoordinate, ScopeCoordinate> mappings;

        private ScopeAlignment(Map<ScopeCoordinate, ScopeCoordinate> mappings) {
            this.mappings = mappings;
        }

        private ScopeAlignment extend(
                ScopeOwner owner,
                Map<Integer, Integer> blockMapping) {
            Map<ScopeCoordinate, ScopeCoordinate> result = new LinkedHashMap<>(mappings);
            for (Map.Entry<Integer, Integer> entry : blockMapping.entrySet()) {
                result.put(
                        new ScopeCoordinate(owner, entry.getKey()),
                        new ScopeCoordinate(owner, entry.getValue()));
            }
            return new ScopeAlignment(result);
        }
    }

    private static final class GlobalBindingIdentity {
        private final boolean parameter;
        private final int ownerPhase;
        private final int coordinate;
        private final String ownerContext;

        private GlobalBindingIdentity(
                boolean parameter,
                int ownerPhase,
                int coordinate,
                String ownerContext) {
            this.parameter = parameter;
            this.ownerPhase = ownerPhase;
            this.coordinate = coordinate;
            this.ownerContext = ownerContext == null ? "" : ownerContext;
        }

        private static GlobalBindingIdentity from(Binding binding) {
            if (binding.role() == BindingRole.PARAMETER) {
                return new GlobalBindingIdentity(true, -1, binding.ordinal(), "");
            }
            return new GlobalBindingIdentity(
                    false,
                    binding.ownerPhase(),
                    binding.coordinate(),
                    binding.ownerContext());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof GlobalBindingIdentity)) {
                return false;
            }
            GlobalBindingIdentity identity = (GlobalBindingIdentity) other;
            return parameter == identity.parameter
                    && ownerPhase == identity.ownerPhase
                    && coordinate == identity.coordinate
                    && ownerContext.equals(identity.ownerContext);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(parameter);
            result = 31 * result + ownerPhase;
            result = 31 * result + coordinate;
            return 31 * result + ownerContext.hashCode();
        }
    }

    private static final class GlobalBinding {
        private final GlobalBindingIdentity identity;
        private final Binding binding;

        private GlobalBinding(GlobalBindingIdentity identity, Binding binding) {
            this.identity = identity;
            this.binding = binding;
        }
    }

    private static final class GlobalBindingIndex {
        private final List<Phase> phases;
        private final int phaseCount;
        private final List<GlobalBinding> bindings;
        private final Map<GlobalBindingIdentity, Integer> indices;
        private final List<int[]> localToGlobal;
        private final List<Map<Integer, Integer>> usedLocalByGlobal;
        private final Set<Integer> used;

        private GlobalBindingIndex(
                List<Phase> phases,
                int phaseCount,
                List<GlobalBinding> bindings,
                Map<GlobalBindingIdentity, Integer> indices,
                List<int[]> localToGlobal,
                List<Map<Integer, Integer>> usedLocalByGlobal,
                Set<Integer> used) {
            this.phases = phases;
            this.phaseCount = phaseCount;
            this.bindings = bindings;
            this.indices = indices;
            this.localToGlobal = localToGlobal;
            this.usedLocalByGlobal = usedLocalByGlobal;
            this.used = used;
        }

        private static GlobalBindingIndex create(
                List<Phase> phases,
                int phaseCount) {
            Map<GlobalBindingIdentity, Integer> indices = new LinkedHashMap<>();
            List<GlobalBinding> bindings = new ArrayList<>();
            List<int[]> localToGlobal = new ArrayList<>(phaseCount);
            for (int phaseIndex = 0; phaseIndex < phaseCount; phaseIndex++) {
                Phase phase = phases.get(phaseIndex);
                int[] local = new int[phase.bindings().size()];
                for (int bindingIndex = 0;
                        bindingIndex < phase.bindings().size(); bindingIndex++) {
                    Binding binding = phase.bindings().get(bindingIndex);
                    GlobalBindingIdentity identity = GlobalBindingIdentity.from(binding);
                    Integer global = indices.get(identity);
                    if (global == null) {
                        global = bindings.size();
                        indices.put(identity, global);
                        bindings.add(new GlobalBinding(identity, binding));
                    } else {
                        requireConsistentGlobalBinding(
                                bindings.get(global).binding, binding);
                    }
                    local[bindingIndex] = global;
                }
                localToGlobal.add(local);
            }
            requireCertifiedOrbitPartitions(bindings);

            Set<Integer> used = new java.util.TreeSet<>();
            List<Map<Integer, Integer>> usedLocalByGlobal =
                    new ArrayList<>(phaseCount);
            for (int phaseIndex = 0; phaseIndex < phaseCount; phaseIndex++) {
                Phase phase = phases.get(phaseIndex);
                Map<Integer, Integer> usedLocals = new HashMap<>();
                for (int local : usedBindings(phase.matrix())) {
                    int global = localToGlobal.get(phaseIndex)[local];
                    Integer previous = usedLocals.put(global, local);
                    if (previous != null && previous != local) {
                        throw new IllegalStateException(
                                "One certified binder coordinate has multiple matrix indices in a phase");
                    }
                    used.add(global);
                }
                usedLocalByGlobal.add(usedLocals);
            }
            return new GlobalBindingIndex(
                    phases,
                    phaseCount,
                    bindings,
                    indices,
                    localToGlobal,
                    usedLocalByGlobal,
                    used);
        }

        private GlobalBinding binding(int index) {
            return bindings.get(index);
        }

        private static void requireCertifiedOrbitPartitions(
                List<GlobalBinding> bindings) {
            for (GlobalBinding current : bindings) {
                if (current.identity.parameter) {
                    continue;
                }
                List<Integer> expected = new ArrayList<>();
                for (GlobalBinding candidate : bindings) {
                    if (!candidate.identity.parameter
                            && current.identity.ownerPhase
                                    == candidate.identity.ownerPhase
                            && current.identity.ownerContext.equals(
                                    candidate.identity.ownerContext)
                            && current.binding.declaration().exchangeClass()
                                    == candidate.binding.declaration().exchangeClass()
                            && current.binding.declaration().sameCertifiedPayload(
                                    candidate.binding.declaration())) {
                        expected.add(candidate.identity.coordinate);
                    }
                }
                expected.sort(Integer::compareTo);
                if (!expected.equals(current.binding.certifiedOrbit())) {
                    throw new IllegalStateException(
                            "A certified binder orbit does not equal its complete "
                                    + "owner-scoped exchange partition");
                }
            }
        }

        private static void requireConsistentGlobalBinding(
                Binding first,
                Binding repeated) {
            if (!first.declaration().sameCertifiedPayload(repeated.declaration())
                    || first.declaration().exchangeClass()
                            != repeated.declaration().exchangeClass()
                    || !first.certifiedOrbit().equals(repeated.certifiedOrbit())
                    || !first.ownerContext().equals(repeated.ownerContext())
                    || first.prenexPathErasureCertified()
                            != repeated.prenexPathErasureCertified()
                    || !first.prenexPathErasureCertified()
                            && !first.bindingPath().equals(repeated.bindingPath())) {
                throw new IllegalStateException(
                        "Inherited binder metadata disagrees with its owning coordinate");
            }
        }
    }

    private static final class MutableStats {
        private long alphaAlignments;
        private long quantifierAlignments;
        private long scopeAlignments;

        private void consumeAlphaAlignment() {
            alphaAlignments = boundedIncrement(
                    alphaAlignments,
                    "acgn.metric.maxAlphaAlignments",
                    DEFAULT_MAX_ALPHA_ALIGNMENTS,
                    "alpha alignments");
        }

        private void consumeQuantifierAlignment() {
            quantifierAlignments = boundedIncrement(
                    quantifierAlignments,
                    "acgn.metric.maxQuantifierAlignments",
                    DEFAULT_MAX_QUANTIFIER_ALIGNMENTS,
                    "minimum quantifier alignments");
        }

        private void consumeScopeAlignment() {
            scopeAlignments = boundedIncrement(
                    scopeAlignments,
                    "acgn.metric.maxScopeAlignments",
                    DEFAULT_MAX_SCOPE_ALIGNMENTS,
                    "scope alignments");
        }

        private static long boundedIncrement(
                long current,
                String property,
                long defaultMaximum,
                String label) {
            long maximum = Long.getLong(property, defaultMaximum);
            if (maximum <= 0) {
                throw new IllegalStateException(property + " must be positive");
            }
            long next = Math.incrementExact(current);
            if (next > maximum) {
                throw new ResourceLimitException(
                        label + " exceed configured exact-search bound " + maximum);
            }
            return next;
        }
    }

    /** Exact evaluation stopped before a minimum was certified. */
    public static final class ResourceLimitException extends RuntimeException {
        private ResourceLimitException(String message) {
            super(message);
        }
    }

    private static final class NodePair {
        private final Node left;
        private final Node right;

        private NodePair(Node left, Node right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NodePair
                    && left == ((NodePair) other).left
                    && right == ((NodePair) other).right;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(left) + System.identityHashCode(right);
        }
    }
}
