package is.fivefivefive.CanDis.theory;

import java.util.Objects;

/** The formal invocation pair {@code m * a}. */
public final class TypedInvocation implements HasSlotSupport {
    private final TypedEClassInterface eclass;
    private final TypedEmbedding embedding;

    public TypedInvocation(TypedEClassInterface eclass, TypedEmbedding embedding) {
        this.eclass = Objects.requireNonNull(eclass, "eclass");
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        if (!eclass.exposedSlots().equals(embedding.source())) {
            throw new IllegalArgumentException(
                    "Invocation embedding source must equal the e-class exposed interface");
        }
    }

    public static TypedInvocation identity(TypedEClassInterface eclass) {
        Objects.requireNonNull(eclass, "eclass");
        return new TypedInvocation(eclass, TypedRenaming.identity(eclass.exposedSlots()));
    }

    public TypedEClassInterface eclass() {
        return eclass;
    }

    public TypedEmbedding embedding() {
        return embedding;
    }

    public TypedSlotContext callerContext() {
        return embedding.codomain();
    }

    public GraphType outputType() {
        return eclass.outputType();
    }

    @Override
    public TypedSlotContext support() {
        return embedding.image();
    }

    /** Applies {@code callerAction} to this invocation as {@code callerAction o embedding}. */
    public TypedInvocation act(TypedEmbedding callerAction) {
        Objects.requireNonNull(callerAction, "callerAction");
        if (!callerContext().equals(callerAction.source())) {
            throw new IllegalArgumentException(
                    "Invocation action source must equal the invocation caller context");
        }
        return new TypedInvocation(eclass, embedding.andThen(callerAction));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypedInvocation)) {
            return false;
        }
        TypedInvocation invocation = (TypedInvocation) other;
        return eclass.equals(invocation.eclass) && embedding.equals(invocation.embedding);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eclass, embedding);
    }

    @Override
    public String toString() {
        return embedding.mapping() + " * " + eclass.id();
    }
}
