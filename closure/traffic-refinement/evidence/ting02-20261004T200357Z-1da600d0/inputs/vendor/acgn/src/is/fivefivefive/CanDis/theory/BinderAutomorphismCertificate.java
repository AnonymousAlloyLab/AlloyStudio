package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Signature proof that one permutation belongs to the declared {@code Aut(beta)}. */
public final class BinderAutomorphismCertificate extends TypedEqualityCertificate {
    private final List<BinderCoordinateDescriptor> coordinates;
    private final TypedSlotContext boundContext;
    private final StructuralKey descriptorKey;
    private final TypedPermutation permutation;
    private final CertificateOrigin origin;

    public BinderAutomorphismCertificate(
            List<? extends BinderCoordinateDescriptor> coordinates,
            TypedPermutation permutation,
            CertificateOrigin origin) {
        this(build(coordinates, permutation, origin));
    }

    private BinderAutomorphismCertificate(Build build) {
        super(
                CertificateCategory.BINDER_AUTOMORPHISM,
                TypedCertificateEndpoint.binderPattern(
                        build.descriptorKey,
                        build.boundContext,
                        TypedPermutation.identity(build.boundContext)),
                TypedCertificateEndpoint.binderPattern(
                        build.descriptorKey,
                        build.boundContext,
                        build.permutation),
                Collections.emptyList(),
                Arrays.asList(
                        build.descriptorKey,
                        TheoryKeys.embedding(build.permutation),
                        build.origin.structuralKey()));
        this.coordinates = build.coordinates;
        this.boundContext = build.boundContext;
        this.descriptorKey = build.descriptorKey;
        this.permutation = build.permutation;
        this.origin = build.origin;
        verifyLocal();
    }

    private static Build build(
            List<? extends BinderCoordinateDescriptor> source,
            TypedPermutation permutation,
            CertificateOrigin origin) {
        List<BinderCoordinateDescriptor> coordinates =
                BinderBlockDescriptor.normalizedCoordinatesForCertificate(source);
        TypedSlotContext context = BinderBlockDescriptor.contextForCoordinates(coordinates);
        StructuralKey key = BinderBlockDescriptor.payloadKeyForCoordinates(coordinates);
        TypedPermutation checked = Objects.requireNonNull(permutation, "permutation");
        BinderBlockDescriptor.requireDescriptorAutomorphism(
                coordinates, context, checked);
        CertificateOrigin checkedOrigin = Objects.requireNonNull(origin, "origin");
        if (checkedOrigin.kind()
                != CertificateOrigin.Kind.SIGNATURE_BINDER_AUTOMORPHISM) {
            throw new IllegalArgumentException(
                    "Binder automorphism requires signature provenance");
        }
        if (checked.equals(TypedPermutation.identity(context))) {
            throw new IllegalArgumentException(
                    "Identity does not need a binder-automorphism certificate");
        }
        return new Build(coordinates, context, key, checked, checkedOrigin);
    }

    public List<BinderCoordinateDescriptor> coordinates() {
        return coordinates;
    }

    public TypedSlotContext boundContext() {
        return boundContext;
    }

    public StructuralKey descriptorKey() {
        return descriptorKey;
    }

    public TypedPermutation permutation() {
        return permutation;
    }

    public CertificateOrigin origin() {
        return origin;
    }

    @Override
    void verifyLocal() {
        if (origin.kind() != CertificateOrigin.Kind.SIGNATURE_BINDER_AUTOMORPHISM
                || !boundContext.equals(permutation.source())
                || !boundContext.equals(permutation.codomain())) {
            throw new IllegalStateException("Malformed binder-automorphism certificate");
        }
        BinderBlockDescriptor.requireDescriptorAutomorphism(
                coordinates, boundContext, permutation);
    }

    private static final class Build {
        private final List<BinderCoordinateDescriptor> coordinates;
        private final TypedSlotContext boundContext;
        private final StructuralKey descriptorKey;
        private final TypedPermutation permutation;
        private final CertificateOrigin origin;

        private Build(
                List<BinderCoordinateDescriptor> coordinates,
                TypedSlotContext boundContext,
                StructuralKey descriptorKey,
                TypedPermutation permutation,
                CertificateOrigin origin) {
            this.coordinates = coordinates;
            this.boundContext = boundContext;
            this.descriptorKey = descriptorKey;
            this.permutation = permutation;
            this.origin = origin;
        }
    }
}
