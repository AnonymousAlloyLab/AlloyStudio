package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Retained source-insertion provenance, including any certified hash collision. */
public final class CertifiedInsertionResult {
    private final CertifiedCanonicalizationResult canonicalization;
    private final TypedEClassInterface insertedClass;
    private final ShapeWitness shapeWitness;
    private final CanonicalOrbitCertificate canonicalOrbit;
    private final FreshWitnessDefinitionCertificate witnessDefinition;
    private final TypedEqualityCertificate shapeEquation;
    private final TypedEqualityCertificate sourceToInsertedClass;
    private final ParentEdgeCertificate collisionEdge;
    private final TypedInvocation returnedInvocation;
    private final TypedEqualityCertificate sourceToReturnedInvocation;
    private final StructuralKey structuralKey;

    CertifiedInsertionResult(
            CertifiedCanonicalizationResult canonicalization,
            TypedEClassInterface insertedClass,
            ShapeWitness shapeWitness,
            CanonicalOrbitCertificate canonicalOrbit,
            FreshWitnessDefinitionCertificate witnessDefinition,
            TypedEqualityCertificate shapeEquation,
            TypedEqualityCertificate sourceToInsertedClass,
            ParentEdgeCertificate collisionEdge,
            TypedInvocation returnedInvocation,
            TypedEqualityCertificate sourceToReturnedInvocation) {
        this.canonicalization = Objects.requireNonNull(
                canonicalization, "canonicalization");
        this.insertedClass = Objects.requireNonNull(insertedClass, "insertedClass");
        this.shapeWitness = Objects.requireNonNull(shapeWitness, "shapeWitness");
        this.canonicalOrbit = Objects.requireNonNull(canonicalOrbit, "canonicalOrbit");
        this.witnessDefinition = Objects.requireNonNull(
                witnessDefinition, "witnessDefinition");
        this.shapeEquation = Objects.requireNonNull(shapeEquation, "shapeEquation");
        this.sourceToInsertedClass = Objects.requireNonNull(
                sourceToInsertedClass, "sourceToInsertedClass");
        this.collisionEdge = collisionEdge;
        this.returnedInvocation = Objects.requireNonNull(
                returnedInvocation, "returnedInvocation");
        this.sourceToReturnedInvocation = Objects.requireNonNull(
                sourceToReturnedInvocation, "sourceToReturnedInvocation");
        validate();

        List<StructuralKey> parts = new ArrayList<>();
        parts.add(canonicalization.structuralKey());
        parts.add(TheoryKeys.eclass(insertedClass));
        parts.add(shapeWitness.structuralKey());
        parts.add(canonicalOrbit.structuralKey());
        parts.add(witnessDefinition.structuralKey());
        parts.add(shapeEquation.structuralKey());
        parts.add(sourceToInsertedClass.structuralKey());
        if (collisionEdge != null) {
            parts.add(collisionEdge.structuralKey());
        }
        parts.add(TheoryKeys.invocation(returnedInvocation));
        parts.add(sourceToReturnedInvocation.structuralKey());
        this.structuralKey = StructuralKey.branch(
                "certified-source-insertion", parts);
    }

    private void validate() {
        CanonicalizationResult structural = canonicalization.structural();
        if (!insertedClass.outputType().equals(structural.shape().outputType())
                || !insertedClass.exposedSlots().equals(structural.effectiveSupport())
                || !shapeWitness.exactSlots().equals(structural.shape().exactSlots())
                || !shapeWitness.ambientSupport().equals(structural.effectiveSupport())
                || !shapeWitness.exposedInterface().equals(insertedClass.exposedSlots())
                || !shapeWitness.instantiatingRenaming().equals(structural.witness())) {
            throw new IllegalArgumentException(
                    "Insertion metadata does not expose the effective kernel interface");
        }
        if (!canonicalOrbit.result().equals(structural)
                || !witnessDefinition.freshClass().equals(insertedClass)) {
            throw new IllegalArgumentException(
                    "Insertion proof objects belong to a different canonical result");
        }

        TypedCertificateEndpoint shapeEndpoint = TypedCertificateEndpoint.node(
                structural.shape().node().act(structural.witness()));
        TypedCertificateEndpoint insertedWitness =
                TypedCertificateEndpoint.eclassWitness(insertedClass);
        CertificateVerifier.verify(shapeEquation);
        if (!shapeEquation.leftEndpoint().equals(shapeEndpoint)
                || !shapeEquation.rightEndpoint().equals(insertedWitness)) {
            throw new IllegalArgumentException(
                    "Stored shape equation has incorrect EC endpoints");
        }

        TypedInvocation insertedInvocation = new TypedInvocation(
                insertedClass, structural.inclusion());
        CertificateVerifier.verify(sourceToInsertedClass);
        if (!sourceToInsertedClass.leftEndpoint().equals(
                    TypedCertificateEndpoint.node(structural.source()))
                || !sourceToInsertedClass.rightEndpoint().equals(
                        TypedCertificateEndpoint.invocation(insertedInvocation))) {
            throw new IllegalArgumentException(
                    "Source-to-fresh-class proof has incorrect endpoints");
        }
        CertificateVerifier.verify(sourceToReturnedInvocation);
        if (!sourceToReturnedInvocation.leftEndpoint().equals(
                    TypedCertificateEndpoint.node(structural.source()))
                || !sourceToReturnedInvocation.rightEndpoint().equals(
                        TypedCertificateEndpoint.invocation(returnedInvocation))) {
            throw new IllegalArgumentException(
                    "Insertion return proof has incorrect endpoints");
        }
        if (collisionEdge != null) {
            CertificateVerifier.verifyParentEdge(collisionEdge);
        }
    }

    public CertifiedCanonicalizationResult canonicalization() {
        return canonicalization;
    }

    public TypedEClassInterface insertedClass() {
        return insertedClass;
    }

    public ShapeWitness shapeWitness() {
        return shapeWitness;
    }

    public CanonicalOrbitCertificate canonicalOrbit() {
        return canonicalOrbit;
    }

    public FreshWitnessDefinitionCertificate witnessDefinition() {
        return witnessDefinition;
    }

    public TypedEqualityCertificate shapeEquation() {
        return shapeEquation;
    }

    public Optional<ParentEdgeCertificate> collisionEdge() {
        return Optional.ofNullable(collisionEdge);
    }

    public boolean collided() {
        return collisionEdge != null;
    }

    public TypedInvocation returnedInvocation() {
        return returnedInvocation;
    }

    public TypedEqualityCertificate sourceToReturnedInvocation() {
        return sourceToReturnedInvocation;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    public List<TypedEqualityCertificate> retainedSourceProofs() {
        List<TypedEqualityCertificate> retained = new ArrayList<>();
        retained.add(canonicalization.d());
        if (canonicalization.sourceConstruction().isPresent()) {
            retained.add(canonicalization.sourceReplay());
        }
        retained.add(canonicalOrbit);
        retained.add(witnessDefinition);
        retained.add(shapeEquation);
        retained.add(sourceToInsertedClass);
        retained.add(sourceToReturnedInvocation);
        return Collections.unmodifiableList(retained);
    }

    @Override
    public String toString() {
        return "insert " + insertedClass + " -> " + returnedInvocation
                + (collided() ? " (collision)" : " (fresh key)");
    }
}
