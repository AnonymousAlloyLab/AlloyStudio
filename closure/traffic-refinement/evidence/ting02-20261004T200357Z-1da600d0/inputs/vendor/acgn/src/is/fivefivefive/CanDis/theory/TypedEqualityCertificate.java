package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Closed base class for finite, typed equational derivation trees. */
public abstract sealed class TypedEqualityCertificate permits
        InputEquationCertificate,
        CongruenceCertificate,
        ParentEdgeCertificate,
        SymmetryCertificate,
        InterfaceRestrictionCertificate,
        ContainerLawCertificate,
        BinderAutomorphismCertificate,
        BinderOccurrenceAutomorphismCertificate,
        ContainerNormalizationCertificate,
        FlatConstructionCertificate,
        ContainerConstructionCertificate,
        DependentChainCertificate,
        KernelReplayCertificate,
        CanonicalOrbitCertificate,
        StructuralAlphaCertificate,
        FreshWitnessDefinitionCertificate,
        RebuildCongruenceCertificate,
        EffectiveShapeCollisionCertificate,
        DerivedEqualityCertificate {
    private final CertificateCategory category;
    private final TypedCertificateEndpoint leftEndpoint;
    private final TypedCertificateEndpoint rightEndpoint;
    private final List<TypedEqualityCertificate> premises;
    private final StructuralKey endpointTypeCheck;
    private final StructuralKey structuralKey;

    TypedEqualityCertificate(
            CertificateCategory category,
            TypedCertificateEndpoint leftEndpoint,
            TypedCertificateEndpoint rightEndpoint,
            List<? extends TypedEqualityCertificate> premises,
            List<? extends StructuralKey> details) {
        this.category = Objects.requireNonNull(category, "category");
        this.leftEndpoint = Objects.requireNonNull(leftEndpoint, "leftEndpoint");
        this.rightEndpoint = Objects.requireNonNull(rightEndpoint, "rightEndpoint");
        if (!leftEndpoint.context().equals(rightEndpoint.context())
                || !leftEndpoint.sort().equals(rightEndpoint.sort())) {
            throw new IllegalArgumentException(
                    "Equality certificate endpoints must have one context and sort");
        }
        Objects.requireNonNull(premises, "premises");
        List<TypedEqualityCertificate> copiedPremises = new ArrayList<>(premises.size());
        for (TypedEqualityCertificate premise : premises) {
            copiedPremises.add(Objects.requireNonNull(premise, "premise"));
        }
        this.premises = Collections.unmodifiableList(copiedPremises);
        this.endpointTypeCheck = StructuralKey.branch(
                "certificate-endpoint-type-check",
                java.util.Arrays.asList(
                        TheoryKeys.context(leftEndpoint.context()),
                        leftEndpoint.sort().structuralKey()));

        List<StructuralKey> children = new ArrayList<>();
        children.add(leftEndpoint.structuralKey());
        children.add(rightEndpoint.structuralKey());
        children.add(endpointTypeCheck);
        Objects.requireNonNull(details, "details");
        for (StructuralKey detail : details) {
            children.add(Objects.requireNonNull(detail, "certificate detail"));
        }
        for (TypedEqualityCertificate premise : copiedPremises) {
            children.add(StructuralKey.branch(
                    "certificate-premise",
                    Collections.singletonList(premise.structuralKey())));
        }
        this.structuralKey = StructuralKey.of(
                "typed-equality-certificate",
                Collections.singletonList(category.name()),
                children);
    }

    public final CertificateCategory category() {
        return category;
    }

    public final TypedCertificateEndpoint leftEndpoint() {
        return leftEndpoint;
    }

    public final TypedCertificateEndpoint rightEndpoint() {
        return rightEndpoint;
    }

    public final TypedSlotContext context() {
        return leftEndpoint.context();
    }

    public final CertificateSort sort() {
        return leftEndpoint.sort();
    }

    public final List<TypedEqualityCertificate> premises() {
        return premises;
    }

    /** The retained successful endpoint context/sort check. */
    public final StructuralKey endpointTypeCheck() {
        return endpointTypeCheck;
    }

    public final StructuralKey structuralKey() {
        return structuralKey;
    }

    abstract void verifyLocal();

    @Override
    public final boolean equals(Object other) {
        return other instanceof TypedEqualityCertificate
                && structuralKey.equals(
                        ((TypedEqualityCertificate) other).structuralKey);
    }

    @Override
    public final int hashCode() {
        return structuralKey.hashCode();
    }

    @Override
    public final String toString() {
        return category + "(" + leftEndpoint + " = " + rightEndpoint + ")";
    }
}
