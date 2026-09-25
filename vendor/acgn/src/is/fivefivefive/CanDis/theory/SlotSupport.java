package is.fivefivefive.CanDis.theory;

import java.util.Objects;

/** Structural support operations used by the Phase B carrier values. */
public final class SlotSupport {
    private SlotSupport() {
    }

    public static TypedSlotContext slot(TypedSlot slot) {
        return TypedSlotContext.singleton(Objects.requireNonNull(slot, "slot"));
    }

    public static TypedSlotContext invocation(TypedInvocation invocation) {
        return Objects.requireNonNull(invocation, "invocation").support();
    }

    public static TypedSlotContext union(Iterable<? extends HasSlotSupport> values) {
        Objects.requireNonNull(values, "values");
        TypedSlotContext result = TypedSlotContext.empty();
        for (HasSlotSupport value : values) {
            result = result.union(Objects.requireNonNull(value, "support value").support());
        }
        return result;
    }

    public static TypedSlotContext unionContexts(Iterable<TypedSlotContext> contexts) {
        Objects.requireNonNull(contexts, "contexts");
        TypedSlotContext result = TypedSlotContext.empty();
        for (TypedSlotContext context : contexts) {
            result = result.union(Objects.requireNonNull(context, "context"));
        }
        return result;
    }

    public static TypedSlotContext bind(TypedSlot bound, TypedSlotContext bodySupport) {
        Objects.requireNonNull(bound, "bound");
        return Objects.requireNonNull(bodySupport, "bodySupport").without(bound);
    }

    public static TypedSlotContext rename(
            TypedEmbedding embedding,
            TypedSlotContext support) {
        return Objects.requireNonNull(embedding, "embedding")
                .imageOf(Objects.requireNonNull(support, "support"));
    }
}
