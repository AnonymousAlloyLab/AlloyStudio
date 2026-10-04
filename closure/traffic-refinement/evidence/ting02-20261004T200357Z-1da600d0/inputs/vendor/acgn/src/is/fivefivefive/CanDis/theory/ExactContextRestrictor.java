package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Regards leader-normalized syntax in its exact free-slot context. */
final class ExactContextRestrictor {
    private ExactContextRestrictor() {
    }

    static TypedENode restrictToSupport(TypedENode node) {
        Objects.requireNonNull(node, "node");
        TypedSlotContext exactContext = node.support();
        if (!exactContext.isSubcontextOf(node.context())) {
            throw new IllegalArgumentException("Node support must lie in its caller context");
        }
        List<PortValue> ports = new ArrayList<>(node.ports().size());
        for (PortValue port : node.ports()) {
            ports.add(restrictPort(port, exactContext));
        }
        TypedENode restricted = node.rebuildCanonicalCandidate(exactContext, ports);
        if (!restricted.support().equals(exactContext)) {
            throw new IllegalStateException("Exact-context restriction changed structural support");
        }
        return restricted;
    }

    private static PortValue restrictPort(
            PortValue port,
            TypedSlotContext targetContext) {
        if (!targetContext.isSubcontextOf(port.context())
                || !port.support().isSubcontextOf(targetContext)) {
            throw new IllegalArgumentException(
                    "Port context can be narrowed only to a context containing its support");
        }
        if (port instanceof OnePort) {
            OnePort one = (OnePort) port;
            if (one.leaf() instanceof SlotPortLeaf) {
                return new OnePort(one.schema(), targetContext, one.leaf());
            }
            TypedInvocation invocation = ((InvocationPortLeaf) one.leaf()).invocation();
            TypedEmbedding narrowed = TypedEmbedding.of(
                    invocation.embedding().source(),
                    targetContext,
                    invocation.embedding().mapping());
            return new OnePort(
                    one.schema(),
                    targetContext,
                    new InvocationPortLeaf(new TypedInvocation(
                            invocation.eclass(), narrowed)));
        }
        if (port instanceof SeqPort) {
            SeqPort sequence = (SeqPort) port;
            return new SeqPort(
                    sequence.schema(),
                    targetContext,
                    restrictElements(sequence.elements(), targetContext));
        }
        if (port instanceof BagPort) {
            BagPort bag = (BagPort) port;
            return new BagPort(
                    bag.schema(),
                    targetContext,
                    restrictElements(bag.occurrences(), targetContext));
        }
        if (port instanceof SetPort) {
            SetPort set = (SetPort) port;
            SetPort restricted = new SetPort(
                    set.schema(),
                    targetContext,
                    restrictElements(set.elements(), targetContext));
            if (restricted.elements().size() != set.elements().size()) {
                throw new IllegalStateException(
                        "Changing only a shared ambient context cannot deduplicate Set elements");
            }
            return restricted;
        }
        if (port instanceof BindPort) {
            BindPort binder = (BindPort) port;
            if (targetContext.contains(binder.boundSlot())) {
                throw new IllegalStateException("Exact free context captured a bound slot");
            }
            TypedSlotContext bodyContext = targetContext.plus(binder.boundSlot());
            return new BindPort(
                    binder.schema(),
                    targetContext,
                    binder.boundSlot(),
                    restrictPort(binder.body(), bodyContext));
        }
        if (port instanceof BindBlockPort) {
            BindBlockPort block = (BindBlockPort) port;
            if (!targetContext.isDisjoint(block.boundContext())) {
                throw new IllegalStateException("Exact free context captured a binder block");
            }
            TypedSlotContext bodyContext = targetContext.union(block.boundContext());
            return new BindBlockPort(
                    block.schema(),
                    targetContext,
                    block.descriptorToOccurrence(),
                    restrictPort(block.body(), bodyContext));
        }
        throw new IllegalStateException("Unhandled port value " + port.getClass().getName());
    }

    private static List<PortValue> restrictElements(
            List<PortValue> values,
            TypedSlotContext targetContext) {
        List<PortValue> result = new ArrayList<>(values.size());
        for (PortValue value : values) {
            result.add(restrictPort(value, targetContext));
        }
        return result;
    }
}
