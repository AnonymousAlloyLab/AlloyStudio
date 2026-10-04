package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Ordered primitive parent steps retained across path compression. */
public final class ParentPath {
    private final TypedEClassInterface start;
    private final TypedEClassInterface end;
    private final List<ParentStep> steps;
    private final TypedEmbedding compositeEmbedding;
    private final StructuralKey structuralKey;

    private ParentPath(
            TypedEClassInterface start,
            TypedEClassInterface end,
            List<? extends ParentStep> steps) {
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
        Objects.requireNonNull(steps, "steps");
        List<ParentStep> copied = new ArrayList<>(steps.size());
        for (ParentStep step : steps) {
            copied.add(Objects.requireNonNull(step, "parent step"));
        }
        this.steps = Collections.unmodifiableList(copied);
        this.compositeEmbedding = validateAndCompose();

        List<StructuralKey> children = new ArrayList<>(copied.size() + 3);
        children.add(TheoryKeys.eclass(start));
        children.add(TheoryKeys.eclass(end));
        children.add(TheoryKeys.embedding(compositeEmbedding));
        for (ParentStep step : copied) {
            children.add(step.structuralKey());
        }
        this.structuralKey = StructuralKey.branch("parent-path", children);
    }

    public static ParentPath identity(TypedEClassInterface eclass) {
        return new ParentPath(eclass, eclass, Collections.emptyList());
    }

    public static ParentPath direct(ParentStep step) {
        Objects.requireNonNull(step, "step");
        return new ParentPath(
                step.child(), step.parent(), Collections.singletonList(step));
    }

    private TypedEmbedding validateAndCompose() {
        if (steps.isEmpty()) {
            if (!start.equals(end)) {
                throw new IllegalArgumentException("An empty parent path must be an identity path");
            }
            return TypedEmbedding.identity(start.exposedSlots());
        }
        if (!steps.get(0).child().equals(start)) {
            throw new IllegalArgumentException("Parent path does not start at its declared child");
        }
        Set<EClassId> seen = new HashSet<>();
        seen.add(start.id());
        TypedEmbedding composite = steps.get(0).embedding();
        TypedEClassInterface previousParent = steps.get(0).parent();
        if (!seen.add(previousParent.id())) {
            throw new IllegalArgumentException("Parent path contains a cycle");
        }
        for (int index = 1; index < steps.size(); index++) {
            ParentStep step = steps.get(index);
            if (!previousParent.equals(step.child())) {
                throw new IllegalArgumentException(
                        "Adjacent parent path steps have incompatible interfaces");
            }
            composite = step.embedding().andThen(composite);
            previousParent = step.parent();
            if (!seen.add(previousParent.id())) {
                throw new IllegalArgumentException("Parent path contains a cycle");
            }
        }
        if (!previousParent.equals(end)) {
            throw new IllegalArgumentException("Parent path does not end at its declared parent");
        }
        return composite;
    }

    /** Concatenates this path {@code a -> b} with {@code after: b -> c}. */
    public ParentPath andThen(ParentPath after) {
        Objects.requireNonNull(after, "after");
        if (!end.equals(after.start)) {
            throw new IllegalArgumentException(
                    "Parent path composition requires matching middle interfaces");
        }
        List<ParentStep> combined = new ArrayList<>(steps.size() + after.steps.size());
        combined.addAll(steps);
        combined.addAll(after.steps);
        return new ParentPath(start, after.end, combined);
    }

    public TypedEClassInterface start() {
        return start;
    }

    public TypedEClassInterface end() {
        return end;
    }

    public List<ParentStep> steps() {
        return steps;
    }

    public TypedEmbedding compositeEmbedding() {
        return compositeEmbedding;
    }

    public boolean isIdentity() {
        return steps.isEmpty();
    }

    public boolean hasCertificates() {
        for (ParentStep step : steps) {
            if (!step.hasCertificate()) {
                return false;
            }
        }
        return true;
    }

    /** Composes the retained primitive (PC) proofs in the path's start context. */
    public TypedEqualityCertificate composedCertificate() {
        if (steps.isEmpty()) {
            return EqualityCertificates.reflexive(
                    TypedCertificateEndpoint.eclassWitness(start));
        }
        if (!hasCertificates()) {
            throw new IllegalStateException(
                    "A structural Phase D path cannot be replayed as a certificate");
        }
        TypedEqualityCertificate result = steps.get(0).certificate();
        TypedEmbedding prefix = steps.get(0).embedding();
        for (int index = 1; index < steps.size(); index++) {
            ParentStep step = steps.get(index);
            TypedEqualityCertificate transported = EqualityCertificates.rename(
                    step.certificate(), prefix);
            result = EqualityCertificates.transitive(result, transported);
            prefix = step.embedding().andThen(prefix);
        }
        TypedCertificateEndpoint expectedRight = TypedCertificateEndpoint.invocation(
                new TypedInvocation(end, compositeEmbedding));
        if (!result.leftEndpoint().equals(
                    TypedCertificateEndpoint.eclassWitness(start))
                || !result.rightEndpoint().equals(expectedRight)) {
            throw new IllegalStateException(
                    "Composed parent certificate has the wrong path endpoints");
        }
        CertificateVerifier.verify(result);
        return result;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ParentPath)) {
            return false;
        }
        ParentPath path = (ParentPath) other;
        return start.equals(path.start)
                && end.equals(path.end)
                && steps.equals(path.steps)
                && compositeEmbedding.equals(path.compositeEmbedding);
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, steps, compositeEmbedding);
    }

    @Override
    public String toString() {
        return start.id() + " -> " + end.id() + " " + compositeEmbedding.mapping();
    }
}
