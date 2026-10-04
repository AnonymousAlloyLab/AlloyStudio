package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Slow independent specification implementation of quotient-first {@code canon_G}. */
final class ExhaustiveGraphCanonicalizer implements TypedGraphCanonicalizer {
    public static final String VERSION = "canon-g-exhaustive-v4";
    private static final ExhaustiveGraphCanonicalizer INSTANCE =
            new ExhaustiveGraphCanonicalizer();

    private ExhaustiveGraphCanonicalizer() {
    }

    static ExhaustiveGraphCanonicalizer instance() {
        return INSTANCE;
    }

    @Override
    public CanonicalizationResult canonicalize(
            TypedSlottedPortEGraph graph,
            TypedENode node) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(node, "node");
        synchronized (graph) {
            graph.requireQuiescentForCanonicalization();
            if (!node.context().equals(node.support())) {
                throw new IllegalArgumentException(
                        "canon_G requires a node whose context is its exact support");
            }
            LeaderKernelResult leaderKernel = graph.extractLeaderKernel(node);
            TypedENode kernel = leaderKernel.kernel();
            TypedSlotContext canonicalContext = kernel.context().canonicalFreeContext();
            BestCandidate best = new BestCandidate();
            CandidateCounter localWorkItems = new CandidateCounter();
            TypedRenamingEnumerator.forEach(
                    canonicalContext,
                    kernel.context(),
                    witness -> enumerateFreeCandidate(
                            graph,
                            kernel,
                            canonicalContext,
                            witness,
                            best,
                            localWorkItems));
            CanonicalizationResult result = best.result(
                    leaderKernel, localWorkItems.count());
            if (!result.verifyWitness(graph)) {
                throw new IllegalStateException(
                        "Exhaustive canon_G returned a witness that does not replay");
            }
            graph.checkInvariants();
            return result;
        }
    }

    private static void enumerateFreeCandidate(
            TypedSlottedPortEGraph graph,
            TypedENode kernel,
            TypedSlotContext canonicalContext,
            TypedRenaming witness,
            BestCandidate best,
            CandidateCounter localWorkItems) {
        TypedRenaming sourceToCanonical = witness.inverse();
        List<PortValue> ports = new ArrayList<>(kernel.ports().size());
        for (PortValue port : kernel.ports()) {
            ports.add(referenceQuotient(
                    graph, port, sourceToCanonical, localWorkItems));
        }
        TypedENode candidate = kernel.rebuildCanonicalCandidate(
                canonicalContext, ports);
        if (!candidate.support().equals(canonicalContext)) {
            throw new CanonicalizationDomainException(
                    "Reference quotient changed exact kernel support from "
                            + canonicalContext + " to " + candidate.support());
        }
        best.consider(CanonicalShape.of(candidate), witness);
    }

    /**
     * Materializes every candidate in each local leader or binder orbit, then
     * returns its least member before the enclosing container is constructed.
     */
    private static PortValue referenceQuotient(
            TypedSlottedPortEGraph graph,
            PortValue port,
            TypedRenaming renaming,
            CandidateCounter localWorkItems) {
        if (!port.context().equals(renaming.source())) {
            throw new IllegalArgumentException(
                    "Reference port renaming must start at the port context");
        }
        if (port instanceof OnePort) {
            return least(enumerateOneOrbit(
                    graph, (OnePort) port, renaming, localWorkItems));
        }
        if (port instanceof SeqPort) {
            SeqPort sequence = (SeqPort) port;
            return new SeqPort(
                    sequence.schema(),
                    renaming.codomain(),
                    referenceElements(
                            graph, sequence.elements(), renaming, localWorkItems));
        }
        if (port instanceof BagPort) {
            BagPort bag = (BagPort) port;
            return new BagPort(
                    bag.schema(),
                    renaming.codomain(),
                    referenceElements(
                            graph, bag.occurrences(), renaming, localWorkItems));
        }
        if (port instanceof SetPort) {
            SetPort set = (SetPort) port;
            return new SetPort(
                    set.schema(),
                    renaming.codomain(),
                    referenceElements(
                            graph, set.elements(), renaming, localWorkItems));
        }
        if (port instanceof BindBlockPort) {
            return least(enumerateBlockOrbit(
                    graph, (BindBlockPort) port, renaming, localWorkItems));
        }

        BindPort binder = (BindPort) port;
        TypedSlot canonicalBound = CanonicalSlotAlphabet.fresh(
                binder.schema().boundType(),
                SlotAlphabet.CANONICAL_BOUND,
                renaming.codomain());
        TypedRenaming bodyRenaming = renaming.disjointExtension(
                binder.boundSlot(), canonicalBound).asRenaming();
        return new BindPort(
                binder.schema(),
                renaming.codomain(),
                canonicalBound,
                referenceQuotient(
                        graph, binder.body(), bodyRenaming, localWorkItems));
    }

    private static List<PortValue> referenceElements(
            TypedSlottedPortEGraph graph,
            List<PortValue> source,
            TypedRenaming renaming,
            CandidateCounter localWorkItems) {
        List<PortValue> result = new ArrayList<>(source.size());
        for (PortValue value : source) {
            result.add(referenceQuotient(
                    graph, value, renaming, localWorkItems));
        }
        return result;
    }

    private static List<PortValue> enumerateOneOrbit(
            TypedSlottedPortEGraph graph,
            OnePort port,
            TypedRenaming renaming,
            CandidateCounter localWorkItems) {
        PortLeaf leaf = port.leaf();
        if (leaf instanceof SlotPortLeaf) {
            localWorkItems.consider();
            return Collections.singletonList(new OnePort(
                    port.schema(),
                    renaming.codomain(),
                    new SlotPortLeaf(renaming.apply(((SlotPortLeaf) leaf).slot()))));
        }

        TypedInvocation leader = ((InvocationPortLeaf) leaf).invocation();
        if (!graph.isLeader(leader.eclass().id())) {
            throw new IllegalStateException(
                    "Reference quotient requires leader-kernel invocations");
        }
        TypedSymmetryGroup group = graph.symmetryGroupForCanonicalization(
                leader.eclass());
        List<PortValue> orbit = new ArrayList<>(group.elements().size());
        for (TypedPermutation permutation : group.elements()) {
            localWorkItems.consider();
            TypedEmbedding embedding = permutation
                    .andThen(leader.embedding())
                    .andThen(renaming);
            orbit.add(new OnePort(
                    port.schema(),
                    renaming.codomain(),
                    new InvocationPortLeaf(new TypedInvocation(
                            leader.eclass(), embedding))));
        }
        return uniqueOrbit(orbit);
    }

    private static List<PortValue> enumerateBlockOrbit(
            TypedSlottedPortEGraph graph,
            BindBlockPort block,
            TypedRenaming freeRenaming,
            CandidateCounter localWorkItems) {
        BinderBlockDescriptor descriptor = block.schema().descriptor();
        TypedRenaming targetOccurrence = descriptor.freshOccurrenceRenaming(
                freeRenaming.codomain());
        BinderAutomorphismGroup automorphisms =
                graph.binderGroupForCanonicalization(descriptor);
        List<PortValue> orbit = new ArrayList<>(automorphisms.elements().size());
        for (TypedPermutation permutation : automorphisms.elements()) {
            localWorkItems.consider();
            TypedRenaming occurrenceRenaming = block.descriptorToOccurrence()
                    .inverse()
                    .andThen(permutation)
                    .andThen(targetOccurrence);
            TypedRenaming bodyRenaming = freeRenaming
                    .disjointUnion(occurrenceRenaming)
                    .asRenaming();
            PortValue body = referenceQuotient(
                    graph, block.body(), bodyRenaming, localWorkItems);
            orbit.add(new BindBlockPort(
                    block.schema(),
                    freeRenaming.codomain(),
                    targetOccurrence,
                    body));
        }
        return uniqueOrbit(orbit);
    }

    private static List<PortValue> uniqueOrbit(List<PortValue> source) {
        NavigableMap<StructuralKey, PortValue> unique = new TreeMap<>();
        for (PortValue value : source) {
            PortValue previous = unique.putIfAbsent(value.structuralKey(), value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalStateException(
                        "Structural key collision between unequal orbit members");
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static PortValue least(List<PortValue> orbit) {
        if (orbit.isEmpty()) {
            throw new IllegalStateException("A local quotient orbit must contain identity");
        }
        PortValue least = orbit.get(0);
        for (int index = 1; index < orbit.size(); index++) {
            PortValue candidate = orbit.get(index);
            int comparison = candidate.structuralKey().compareTo(least.structuralKey());
            if (comparison == 0 && !candidate.equals(least)) {
                throw new IllegalStateException(
                        "Structural key collision between unequal canonical ports");
            }
            if (comparison < 0) {
                least = candidate;
            }
        }
        return least;
    }

    @Override
    public String version() {
        return VERSION;
    }

    private static final class BestCandidate {
        private final LeastOption<Candidate> minimum = new LeastOption<>(
                Candidate::compare);

        void consider(CanonicalShape candidateShape, TypedRenaming candidateWitness) {
            minimum.consider(new Candidate(candidateShape, candidateWitness));
        }

        CanonicalizationResult result(
                LeaderKernelResult leaderKernel,
                long localQuotientWorkItems) {
            Candidate selected = minimum.orElseThrow(() -> new IllegalStateException(
                    "No typed free-slot bijection was available to canon_G"));
            return new CanonicalizationResult(
                    leaderKernel,
                    selected.shape(),
                    selected.witness(),
                    CanonicalizationMetrics.from(
                            leaderKernel,
                            minimum.considered(),
                            localQuotientWorkItems));
        }
    }

    private static final class CandidateCounter {
        private long count;

        private void consider() {
            count = Math.addExact(count, 1L);
        }

        private long count() {
            return count;
        }
    }

    private record Candidate(CanonicalShape shape, TypedRenaming witness) {
        private Candidate {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(witness, "witness");
        }

        private static int compare(Candidate left, Candidate right) {
            int compared = left.shape.compareTo(right.shape);
            return compared != 0
                    ? compared
                    : TheoryKeys.witnessOrder(left.witness)
                            .compareTo(TheoryKeys.witnessOrder(right.witness));
        }
    }
}
