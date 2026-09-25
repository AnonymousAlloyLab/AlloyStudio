package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Streaming production implementation of quotient-first {@code canon_G}. */
public final class ProductionGraphCanonicalizer implements TypedGraphCanonicalizer {
    public static final String VERSION = "canon-g-production-v4";
    private static final long DEFAULT_MAX_CANONICAL_WORK_ITEMS = 2_000_000L;
    private static final int DEFAULT_MAX_CANONICAL_DEPTH = 512;
    private static final ProductionGraphCanonicalizer INSTANCE =
            new ProductionGraphCanonicalizer();

    private ProductionGraphCanonicalizer() {
    }

    static ProductionGraphCanonicalizer instance() {
        return INSTANCE;
    }

    @Override
    public CanonicalizationResult canonicalize(
            TypedSlottedPortEGraph graph,
            TypedENode node) {
        return canonicalize(graph, node, true);
    }

    CanonicalizationResult canonicalizeWithoutCompression(
            TypedSlottedPortEGraph graph,
            TypedENode node) {
        return canonicalize(graph, node, false);
    }

    private CanonicalizationResult canonicalize(
            TypedSlottedPortEGraph graph,
            TypedENode node,
            boolean allowCompression) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(node, "node");
        synchronized (graph) {
            graph.requireQuiescentForCanonicalization();
            requireExactSource(node);
            requireBoundedCanonicalSyntax(node);
            LeaderKernelResult leaderKernel = allowCompression
                    ? graph.extractLeaderKernel(node)
                    : graph.extractLeaderKernelWithoutCompression(node);
            TypedENode kernel = leaderKernel.kernel();
            BestCandidate best = new BestCandidate();
            CanonicalWorkBudget workBudget = new CanonicalWorkBudget();
            CandidateCounter globalCandidates = new CandidateCounter(workBudget);
            CandidateCounter localWorkItems = new CandidateCounter(workBudget);
            TypedSlotContext canonicalContext = kernel.context().canonicalFreeContext();
            TypedRenamingEnumerator.forEach(
                    canonicalContext,
                    kernel.context(),
                    witness -> considerRenaming(
                            graph,
                            kernel,
                            canonicalContext,
                            witness,
                            best,
                            globalCandidates,
                            localWorkItems));
            CanonicalizationResult result = best.result(
                    leaderKernel,
                    CanonicalizationMetrics.from(
                            leaderKernel,
                            globalCandidates.count(),
                            localWorkItems.count()));
            if (!result.verifyWitness(graph)) {
                throw new IllegalStateException(
                        "Production canon_G returned a witness that does not replay");
            }
            graph.checkInvariants();
            return result;
        }
    }

    private static void requireExactSource(TypedENode node) {
        if (!node.context().equals(node.support())) {
            throw new IllegalArgumentException(
                    "canon_G requires a node whose context is its exact support");
        }
    }

    private static void requireBoundedCanonicalSyntax(TypedENode node) {
        int maximum = maximumCanonicalDepth();
        ArrayDeque<PortDepth> pending = new ArrayDeque<>();
        for (PortValue port : node.ports()) {
            pending.addLast(new PortDepth(port, 1));
        }
        while (!pending.isEmpty()) {
            PortDepth current = pending.removeFirst();
            if (current.depth() > maximum) {
                throw new CanonicalizationDomainException(
                        "canon_G port depth exceeds configured bound " + maximum);
            }
            PortValue port = current.port();
            if (port instanceof BindPort) {
                pending.addLast(new PortDepth(
                        ((BindPort) port).body(), current.depth() + 1));
            } else if (port instanceof BindBlockPort) {
                pending.addLast(new PortDepth(
                        ((BindBlockPort) port).body(), current.depth() + 1));
            } else if (port instanceof SeqPort) {
                addDepths(pending, ((SeqPort) port).elements(), current.depth());
            } else if (port instanceof BagPort) {
                addDepths(pending, ((BagPort) port).occurrences(), current.depth());
            } else if (port instanceof SetPort) {
                addDepths(pending, ((SetPort) port).elements(), current.depth());
            }
        }
    }

    private static void addDepths(
            ArrayDeque<PortDepth> pending,
            List<? extends PortValue> children,
            int parentDepth) {
        for (PortValue child : children) {
            pending.addLast(new PortDepth(child, parentDepth + 1));
        }
    }

    private static int maximumCanonicalDepth() {
        int maximum = Integer.getInteger(
                "acgn.maxCanonicalRecursionDepth", DEFAULT_MAX_CANONICAL_DEPTH);
        if (maximum <= 0) {
            throw new IllegalStateException(
                    "acgn.maxCanonicalRecursionDepth must be positive");
        }
        return maximum;
    }

    private static void considerRenaming(
            TypedSlottedPortEGraph graph,
            TypedENode node,
            TypedSlotContext canonicalContext,
            TypedRenaming witness,
            BestCandidate best,
            CandidateCounter globalCandidates,
            CandidateCounter localWorkItems) {
        globalCandidates.consider();
        TypedRenaming sourceToCanonical = witness.inverse();
        List<PortValue> ports = new ArrayList<>(node.ports().size());
        for (PortValue port : node.ports()) {
            ports.add(quotientPort(
                    graph, port, sourceToCanonical, localWorkItems));
        }
        TypedENode candidate = node.rebuildCanonicalCandidate(canonicalContext, ports);
        requireCanonicalSupport(candidate, canonicalContext);
        best.consider(CanonicalShape.of(candidate), witness);
    }

    private static PortValue quotientPort(
            TypedSlottedPortEGraph graph,
            PortValue port,
            TypedRenaming renaming,
            CandidateCounter localWorkItems) {
        if (!port.context().equals(renaming.source())) {
            throw new IllegalArgumentException(
                    "Canonical port renaming must start at the port context");
        }
        if (port instanceof OnePort) {
            return canonicalOne(
                    graph, (OnePort) port, renaming, localWorkItems);
        }
        if (port instanceof SeqPort) {
            SeqPort sequence = (SeqPort) port;
            List<PortValue> elements = quotientElements(
                    graph, sequence.elements(), renaming, localWorkItems);
            return new SeqPort(sequence.schema(), renaming.codomain(), elements);
        }
        if (port instanceof BagPort) {
            BagPort bag = (BagPort) port;
            List<PortValue> occurrences = quotientElements(
                    graph, bag.occurrences(), renaming, localWorkItems);
            return new BagPort(bag.schema(), renaming.codomain(), occurrences);
        }
        if (port instanceof SetPort) {
            SetPort set = (SetPort) port;
            List<PortValue> elements = quotientElements(
                    graph, set.elements(), renaming, localWorkItems);
            return new SetPort(set.schema(), renaming.codomain(), elements);
        }
        if (port instanceof BindBlockPort) {
            return quotientBlock(
                    graph, (BindBlockPort) port, renaming, localWorkItems);
        }
        BindPort binder = (BindPort) port;
        TypedSlot canonicalBound = CanonicalSlotAlphabet.fresh(
                binder.schema().boundType(),
                SlotAlphabet.CANONICAL_BOUND,
                renaming.codomain());
        TypedRenaming extended = renaming.disjointExtension(
                binder.boundSlot(), canonicalBound).asRenaming();
        PortValue body = quotientPort(
                graph, binder.body(), extended, localWorkItems);
        return new BindPort(
                binder.schema(), renaming.codomain(), canonicalBound, body);
    }

    private static BindBlockPort quotientBlock(
            TypedSlottedPortEGraph graph,
            BindBlockPort block,
            TypedRenaming freeRenaming,
            CandidateCounter localWorkItems) {
        BinderBlockDescriptor descriptor = block.schema().descriptor();
        TypedRenaming targetOccurrence = descriptor.freshOccurrenceRenaming(
                freeRenaming.codomain());
        LeastOption<BindBlockPort> best = new LeastOption<>(
                Comparator.comparing(BindBlockPort::structuralKey));
        graph.binderGroupForCanonicalization(descriptor).forEachElement(permutation -> {
            localWorkItems.consider();
            TypedRenaming boundRenaming = block.descriptorToOccurrence()
                    .inverse()
                    .andThen(permutation)
                    .andThen(targetOccurrence);
            TypedRenaming bodyRenaming = freeRenaming
                    .disjointUnion(boundRenaming)
                    .asRenaming();
            PortValue body = quotientPort(
                    graph, block.body(), bodyRenaming, localWorkItems);
            BindBlockPort candidate = new BindBlockPort(
                    block.schema(),
                    freeRenaming.codomain(),
                    targetOccurrence,
                    body);
            best.consider(candidate);
        });
        return best.orElseThrow(() -> new IllegalStateException(
                "A binder automorphism group must contain identity"));
    }

    private static List<PortValue> quotientElements(
            TypedSlottedPortEGraph graph,
            List<PortValue> elements,
            TypedRenaming renaming,
            CandidateCounter localWorkItems) {
        List<PortValue> result = new ArrayList<>(elements.size());
        for (PortValue element : elements) {
            result.add(quotientPort(
                    graph, element, renaming, localWorkItems));
        }
        return result;
    }

    private static OnePort canonicalOne(
            TypedSlottedPortEGraph graph,
            OnePort port,
            TypedRenaming renaming,
            CandidateCounter localWorkItems) {
        PortLeaf leaf = port.leaf();
        if (leaf instanceof SlotPortLeaf) {
            localWorkItems.consider();
            return new OnePort(
                    port.schema(),
                    renaming.codomain(),
                    new SlotPortLeaf(renaming.apply(((SlotPortLeaf) leaf).slot())));
        }

        TypedInvocation leader = ((InvocationPortLeaf) leaf).invocation();
        if (!graph.isLeader(leader.eclass().id())) {
            throw new IllegalStateException(
                    "Quotient normalization requires leader-kernel invocations");
        }
        TypedSymmetryGroup group = graph.symmetryGroupForCanonicalization(
                leader.eclass());
        LeastOption<OnePort> best = new LeastOption<>(
                Comparator.comparing(OnePort::structuralKey));
        group.forEachElement(permutation -> {
            localWorkItems.consider();
            TypedEmbedding embedding = permutation
                    .andThen(leader.embedding())
                    .andThen(renaming);
            OnePort candidate = new OnePort(
                    port.schema(),
                    renaming.codomain(),
                    new InvocationPortLeaf(new TypedInvocation(
                            leader.eclass(), embedding)));
            best.consider(candidate);
        });
        return best.orElseThrow(() -> new IllegalStateException(
                "A typed symmetry group must contain identity"));
    }

    private static void requireCanonicalSupport(
            TypedENode candidate,
            TypedSlotContext canonicalContext) {
        if (!candidate.support().equals(canonicalContext)) {
            throw new CanonicalizationDomainException(
                    "Quotient normalization changed exact kernel support from "
                            + canonicalContext + " to " + candidate.support());
        }
    }

    @Override
    public String version() {
        return VERSION;
    }

    static int compareCandidatePairForTesting(
            CanonicalShape leftShape,
            TypedRenaming leftWitness,
            CanonicalShape rightShape,
            TypedRenaming rightWitness) {
        return Candidate.compare(
                new Candidate(leftShape, leftWitness),
                new Candidate(rightShape, rightWitness));
    }

    private static final class BestCandidate {
        private final LeastOption<Candidate> minimum = new LeastOption<>(
                Candidate::compare);

        void consider(CanonicalShape candidateShape, TypedRenaming candidateWitness) {
            minimum.consider(new Candidate(candidateShape, candidateWitness));
        }

        CanonicalizationResult result(
                LeaderKernelResult leaderKernel,
                CanonicalizationMetrics metrics) {
            Candidate selected = minimum.orElseThrow(() -> new IllegalStateException(
                    "No typed free-slot bijection was available to canon_G"));
            return new CanonicalizationResult(
                    leaderKernel, selected.shape(), selected.witness(), metrics);
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

    private static final class CandidateCounter {
        private final CanonicalWorkBudget workBudget;
        private long count;

        private CandidateCounter(CanonicalWorkBudget workBudget) {
            this.workBudget = Objects.requireNonNull(workBudget, "workBudget");
        }

        void consider() {
            count = Math.addExact(count, 1L);
            workBudget.consume();
        }

        long count() {
            return count;
        }
    }

    private record PortDepth(PortValue port, int depth) {
        private PortDepth {
            Objects.requireNonNull(port, "port");
        }
    }

    private static final class CanonicalWorkBudget {
        private long consumed;

        private void consume() {
            consumed = Math.addExact(consumed, 1L);
            if (consumed > maximumCanonicalWorkItems()) {
                throw new CanonicalizationDomainException(
                        "canon_G work exceeds configured bound "
                                + maximumCanonicalWorkItems());
            }
        }

        private static long maximumCanonicalWorkItems() {
            long maximum = Long.getLong(
                    "acgn.maxCanonicalWorkItems", DEFAULT_MAX_CANONICAL_WORK_ITEMS);
            if (maximum <= 0) {
                throw new IllegalStateException(
                        "acgn.maxCanonicalWorkItems must be positive");
            }
            return maximum;
        }
    }
}
