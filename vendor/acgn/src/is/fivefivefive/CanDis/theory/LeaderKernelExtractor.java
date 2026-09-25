package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Phase DA implementation of structural {@code leaderKernelTrace_G}. */
final class LeaderKernelExtractor {
    public static final String VERSION = "leader-kernel-trace-v1";
    private static final LeaderKernelExtractor INSTANCE = new LeaderKernelExtractor();

    private LeaderKernelExtractor() {
    }

    static LeaderKernelExtractor instance() {
        return INSTANCE;
    }

    LeaderKernelResult extract(
            TypedSlottedPortEGraph graph,
            TypedENode node) {
        return extract(graph, node, true);
    }

    LeaderKernelResult extractWithoutCompression(
            TypedSlottedPortEGraph graph,
            TypedENode node) {
        return extract(graph, node, false);
    }

    private LeaderKernelResult extract(
            TypedSlottedPortEGraph graph,
            TypedENode node,
            boolean allowCompression) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(node, "node");
        synchronized (graph) {
            graph.requireCertifiedNodeTheoryForCanonicalization(node);
            graph.requireQuiescentForCanonicalization();
            if (!node.context().equals(node.support())) {
                throw new IllegalArgumentException(
                        "leaderKernelTrace_G requires a node whose context is its exact support");
            }
            List<LeaderPortTrace> traces = new ArrayList<>(node.ports().size());
            List<PortValue> normalizedPorts = new ArrayList<>(node.ports().size());
            for (PortValue port : node.ports()) {
                LeaderPortTrace trace = normalizePort(graph, port, allowCompression);
                traces.add(trace);
                normalizedPorts.add(trace.normalizedPort());
            }
            TypedENode ambientLeaderNode = node.rebuildCanonicalCandidate(
                    node.context(), normalizedPorts);
            LeaderKernelTrace trace = new LeaderKernelTrace(
                    node, ambientLeaderNode, traces);
            TypedENode kernel = ExactContextRestrictor.restrictToSupport(
                    ambientLeaderNode);
            TypedEmbedding inclusion = TypedEmbedding.inclusion(
                    kernel.context(), node.context());
            LeaderKernelResult result = new LeaderKernelResult(
                    node, ambientLeaderNode, kernel, inclusion, trace);
            graph.checkInvariants();
            return result;
        }
    }

    public String version() {
        return VERSION;
    }

    private static LeaderPortTrace normalizePort(
            TypedSlottedPortEGraph graph,
            PortValue port,
            boolean allowCompression) {
        if (port instanceof OnePort) {
            OnePort one = (OnePort) port;
            if (one.leaf() instanceof SlotPortLeaf) {
                return LeaderPortTrace.slot(one);
            }
            TypedInvocation invocation = ((InvocationPortLeaf) one.leaf()).invocation();
            TypedFindResult find = graph.findForCanonicalization(
                    invocation, allowCompression);
            OnePort normalized = new OnePort(
                    one.schema(),
                    one.context(),
                    new InvocationPortLeaf(find.leaderInvocation()));
            return LeaderPortTrace.invocation(one, normalized, find);
        }
        if (port instanceof SeqPort) {
            SeqPort sequence = (SeqPort) port;
            return normalizeContainer(
                    graph,
                    sequence,
                    sequence.elements(),
                    allowCompression,
                    values -> new SeqPort(sequence.schema(), sequence.context(), values));
        }
        if (port instanceof BagPort) {
            BagPort bag = (BagPort) port;
            return normalizeContainer(
                    graph,
                    bag,
                    bag.occurrences(),
                    allowCompression,
                    values -> new BagPort(bag.schema(), bag.context(), values));
        }
        if (port instanceof SetPort) {
            SetPort set = (SetPort) port;
            return normalizeContainer(
                    graph,
                    set,
                    set.elements(),
                    allowCompression,
                    values -> new SetPort(set.schema(), set.context(), values));
        }
        if (port instanceof BindPort) {
            BindPort binder = (BindPort) port;
            LeaderPortTrace body = normalizePort(
                    graph, binder.body(), allowCompression);
            BindPort normalized = new BindPort(
                    binder.schema(),
                    binder.context(),
                    binder.boundSlot(),
                    body.normalizedPort());
            return LeaderPortTrace.bind(binder, normalized, body);
        }
        if (port instanceof BindBlockPort) {
            BindBlockPort block = (BindBlockPort) port;
            LeaderPortTrace body = normalizePort(
                    graph, block.body(), allowCompression);
            BindBlockPort normalized = new BindBlockPort(
                    block.schema(),
                    block.context(),
                    block.descriptorToOccurrence(),
                    body.normalizedPort());
            return LeaderPortTrace.bindBlock(block, normalized, body);
        }
        throw new IllegalStateException("Unhandled port value " + port.getClass().getName());
    }

    private static LeaderPortTrace normalizeContainer(
            TypedSlottedPortEGraph graph,
            PortValue source,
            List<PortValue> sourceElements,
            boolean allowCompression,
            ContainerFactory factory) {
        List<LeaderPortTrace> children = new ArrayList<>(sourceElements.size());
        List<PortValue> normalizedInputs = new ArrayList<>(sourceElements.size());
        for (PortValue element : sourceElements) {
            LeaderPortTrace child = normalizePort(
                    graph, element, allowCompression);
            children.add(child);
            normalizedInputs.add(child.normalizedPort());
        }
        PortValue normalized = factory.create(normalizedInputs);
        ContainerNormalizationTrace normalization = ContainerNormalizationTrace.of(
                source, normalizedInputs, normalized);
        return LeaderPortTrace.container(source, normalized, children, normalization);
    }

    @FunctionalInterface
    private interface ContainerFactory {
        PortValue create(List<? extends PortValue> values);
    }
}
