package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Factorization proof and shape transport for one proposed interface restriction. */
public final class InterfaceRestrictionCertificate extends TypedEqualityCertificate {
    private final TypedEClassInterface originalInterface;
    private final TypedEClassInterface restrictedInterface;
    private final TypedEmbedding inclusion;
    private final TypedCertificateEndpoint restrictedWitness;
    private final TypedEqualityCertificate factorization;
    private final NavigableMap<CanonicalShape, ShapeWitness> transportedShapeWitnesses;

    public InterfaceRestrictionCertificate(
            TypedEClassRecord originalRecord,
            TypedSlotContext restrictedContext,
            TypedEqualityCertificate factorization) {
        this(build(originalRecord, restrictedContext, factorization));
    }

    private InterfaceRestrictionCertificate(Build build) {
        super(
                CertificateCategory.INTERFACE_RESTRICTION,
                build.left,
                build.right,
                Collections.singletonList(build.factorization),
                build.details());
        this.originalInterface = build.originalInterface;
        this.restrictedInterface = build.restrictedInterface;
        this.inclusion = build.inclusion;
        this.restrictedWitness = build.restrictedWitness;
        this.factorization = build.factorization;
        this.transportedShapeWitnesses = build.transportedShapeWitnesses;
        verifyLocal();
    }

    private static Build build(
            TypedEClassRecord originalRecord,
            TypedSlotContext restrictedContext,
            TypedEqualityCertificate factorization) {
        Objects.requireNonNull(originalRecord, "originalRecord");
        Objects.requireNonNull(restrictedContext, "restrictedContext");
        TypedEClassInterface original = originalRecord.interfaceView();
        if (!restrictedContext.isSubcontextOf(original.exposedSlots())
                || restrictedContext.equals(original.exposedSlots())) {
            throw new IllegalArgumentException(
                    "Interface restriction requires a proper typed subcontext");
        }
        TypedEClassInterface restricted = new TypedEClassInterface(
                original.id(), original.outputType(), restrictedContext);
        TypedEmbedding inclusion = TypedEmbedding.inclusion(
                restrictedContext, original.exposedSlots());
        TypedCertificateEndpoint restrictedWitness =
                TypedCertificateEndpoint.restrictedWitness(original, restrictedContext);
        TypedCertificateEndpoint left = TypedCertificateEndpoint.eclassWitness(original);
        TypedCertificateEndpoint right = restrictedWitness.act(inclusion);
        CertificateVerifier.verify(Objects.requireNonNull(factorization, "factorization"));
        TypedEqualityCertificate oriented = EqualityCertificates.orient(
                factorization, left, right);

        NavigableMap<CanonicalShape, ShapeWitness> transported = new TreeMap<>();
        for (Map.Entry<CanonicalShape, ShapeWitness> entry
                : originalRecord.shapeWitnesses().entrySet()) {
            ShapeWitness witness = entry.getValue();
            ShapeWitness replacement = new ShapeWitness(
                    witness.exactSlots(),
                    witness.ambientSupport(),
                    restrictedContext,
                    witness.instantiatingRenaming());
            transported.put(entry.getKey(), replacement);
        }
        return new Build(
                original,
                restricted,
                inclusion,
                restrictedWitness,
                oriented,
                left,
                right,
                transported);
    }

    public TypedEClassInterface originalInterface() {
        return originalInterface;
    }

    public TypedEClassInterface restrictedInterface() {
        return restrictedInterface;
    }

    public TypedEmbedding inclusion() {
        return inclusion;
    }

    public TypedCertificateEndpoint restrictedWitness() {
        return restrictedWitness;
    }

    public TypedEqualityCertificate factorization() {
        return factorization;
    }

    public NavigableMap<CanonicalShape, ShapeWitness> transportedShapeWitnesses() {
        return transportedShapeWitnesses;
    }

    @Override
    void verifyLocal() {
        if (!restrictedInterface.exposedSlots().isSubcontextOf(
                    originalInterface.exposedSlots())
                || restrictedInterface.exposedSlots().equals(
                        originalInterface.exposedSlots())
                || !inclusion.equals(TypedEmbedding.inclusion(
                        restrictedInterface.exposedSlots(),
                        originalInterface.exposedSlots()))
                || !leftEndpoint().equals(
                        TypedCertificateEndpoint.eclassWitness(originalInterface))
                || !rightEndpoint().equals(restrictedWitness.act(inclusion))) {
            throw new IllegalStateException("Malformed interface-restriction certificate");
        }
    }

    private static final class Build {
        private final TypedEClassInterface originalInterface;
        private final TypedEClassInterface restrictedInterface;
        private final TypedEmbedding inclusion;
        private final TypedCertificateEndpoint restrictedWitness;
        private final TypedEqualityCertificate factorization;
        private final TypedCertificateEndpoint left;
        private final TypedCertificateEndpoint right;
        private final NavigableMap<CanonicalShape, ShapeWitness> transportedShapeWitnesses;

        private Build(
                TypedEClassInterface originalInterface,
                TypedEClassInterface restrictedInterface,
                TypedEmbedding inclusion,
                TypedCertificateEndpoint restrictedWitness,
                TypedEqualityCertificate factorization,
                TypedCertificateEndpoint left,
                TypedCertificateEndpoint right,
                NavigableMap<CanonicalShape, ShapeWitness> transportedShapeWitnesses) {
            this.originalInterface = originalInterface;
            this.restrictedInterface = restrictedInterface;
            this.inclusion = inclusion;
            this.restrictedWitness = restrictedWitness;
            this.factorization = factorization;
            this.left = left;
            this.right = right;
            this.transportedShapeWitnesses = Collections.unmodifiableNavigableMap(
                    new TreeMap<>(transportedShapeWitnesses));
        }

        private java.util.List<StructuralKey> details() {
            java.util.List<StructuralKey> result = new ArrayList<>();
            result.add(TheoryKeys.eclass(originalInterface));
            result.add(TheoryKeys.eclass(restrictedInterface));
            result.add(TheoryKeys.embedding(inclusion));
            for (Map.Entry<CanonicalShape, ShapeWitness> entry
                    : transportedShapeWitnesses.entrySet()) {
                result.add(StructuralKey.branch(
                        "restriction/transported-shape",
                        java.util.Arrays.asList(
                                entry.getKey().structuralKey(),
                                entry.getValue().structuralKey())));
            }
            return result;
        }
    }
}
