package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Objects;

/** Opaque invocation summand of {@code Port_Gamma(One(tau))}. */
public final class InvocationPortLeaf implements PortLeaf {
    private final TypedInvocation invocation;

    public InvocationPortLeaf(TypedInvocation invocation) {
        this.invocation = Objects.requireNonNull(invocation, "invocation");
    }

    public TypedInvocation invocation() {
        return invocation;
    }

    @Override
    public GraphType type() {
        return invocation.outputType();
    }

    @Override
    public TypedSlotContext support() {
        return invocation.support();
    }

    @Override
    public InvocationPortLeaf act(TypedEmbedding embedding) {
        return new InvocationPortLeaf(invocation.act(Objects.requireNonNull(embedding, "embedding")));
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.branch(
                "port-leaf/invocation",
                Collections.singletonList(TheoryKeys.invocation(invocation)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InvocationPortLeaf
                && invocation.equals(((InvocationPortLeaf) other).invocation);
    }

    @Override
    public int hashCode() {
        return invocation.hashCode();
    }

    @Override
    public String toString() {
        return invocation.toString();
    }
}
