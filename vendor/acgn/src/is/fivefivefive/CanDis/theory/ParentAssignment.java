package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Current total union-find assignment plus its uncompressed primitive path. */
public final class ParentAssignment {
    private final TypedEClassInterface child;
    private final TypedInvocation parentInvocation;
    private final ParentPath provenancePath;
    private final StructuralKey structuralKey;

    private ParentAssignment(
            TypedEClassInterface child,
            TypedInvocation parentInvocation,
            ParentPath provenancePath) {
        this.child = Objects.requireNonNull(child, "child");
        this.parentInvocation = Objects.requireNonNull(
                parentInvocation, "parentInvocation");
        this.provenancePath = Objects.requireNonNull(provenancePath, "provenancePath");
        if (!child.equals(provenancePath.start())) {
            throw new IllegalArgumentException("Parent path must start at the assigned child");
        }
        if (!parentInvocation.eclass().equals(provenancePath.end())) {
            throw new IllegalArgumentException("Parent path must end at the assigned parent");
        }
        if (!parentInvocation.embedding().equals(provenancePath.compositeEmbedding())) {
            throw new IllegalArgumentException(
                    "Parent assignment embedding must equal its path composite");
        }
        if (!child.outputType().equals(parentInvocation.outputType())) {
            throw new IllegalArgumentException("Parent assignment must preserve output type");
        }
        if (!child.exposedSlots().equals(parentInvocation.callerContext())) {
            throw new IllegalArgumentException(
                    "Parent assignment embedding has the wrong child codomain");
        }
        this.structuralKey = StructuralKey.branch(
                "parent-assignment",
                Arrays.asList(
                        TheoryKeys.eclass(child),
                        TheoryKeys.invocation(parentInvocation),
                        provenancePath.structuralKey()));
    }

    public static ParentAssignment root(TypedEClassInterface eclass) {
        Objects.requireNonNull(eclass, "eclass");
        return new ParentAssignment(
                eclass,
                TypedInvocation.identity(eclass),
                ParentPath.identity(eclass));
    }

    public static ParentAssignment direct(ParentStep step) {
        Objects.requireNonNull(step, "step");
        return new ParentAssignment(
                step.child(), step.parentInvocation(), ParentPath.direct(step));
    }

    public static ParentAssignment compressed(ParentPath path) {
        Objects.requireNonNull(path, "path");
        return new ParentAssignment(
                path.start(),
                new TypedInvocation(path.end(), path.compositeEmbedding()),
                path);
    }

    public TypedEClassInterface child() {
        return child;
    }

    public TypedInvocation parentInvocation() {
        return parentInvocation;
    }

    public ParentPath provenancePath() {
        return provenancePath;
    }

    public boolean isRoot() {
        return child.equals(parentInvocation.eclass())
                && provenancePath.isIdentity()
                && parentInvocation.embedding().equals(
                        TypedEmbedding.identity(child.exposedSlots()));
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ParentAssignment)) {
            return false;
        }
        ParentAssignment assignment = (ParentAssignment) other;
        return child.equals(assignment.child)
                && parentInvocation.equals(assignment.parentInvocation)
                && provenancePath.equals(assignment.provenancePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(child, parentInvocation, provenancePath);
    }

    @Override
    public String toString() {
        return "U(" + child.id() + ")=" + parentInvocation;
    }
}
