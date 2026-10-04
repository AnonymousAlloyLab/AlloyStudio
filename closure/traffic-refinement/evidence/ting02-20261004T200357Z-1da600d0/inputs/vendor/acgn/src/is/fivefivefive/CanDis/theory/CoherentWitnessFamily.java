package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable compact realization of a coherent witness family.
 *
 * <p>The e-class witness endpoints stand for the terms {@code w_a}. The
 * retained maps reconstruct the EC, PC, and SC clauses without choosing or
 * serializing concrete source terms.</p>
 */
public final class CoherentWitnessFamily {
    private final TypedSlottedPortEGraph owner;
    private final long graphRevision;
    private final NavigableMap<ParentRecordKey, TypedEqualityCertificate>
            shapeCoherence;
    private final NavigableMap<EClassId, TypedEqualityCertificate> parentCoherence;
    private final NavigableMap<EClassId, List<TypedEqualityCertificate>>
            symmetryCoherence;
    private final StructuralKey structuralKey;

    private CoherentWitnessFamily(
            TypedSlottedPortEGraph owner,
            long graphRevision,
            Map<EClassId, TypedEClassRecord> records,
            Map<EClassId, ParentAssignment> assignments,
            Map<ParentRecordKey, TypedEqualityCertificate> shapeCertificates) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.graphRevision = graphRevision;
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(assignments, "assignments");
        Objects.requireNonNull(shapeCertificates, "shapeCertificates");
        if (!records.keySet().equals(assignments.keySet())) {
            throw new IllegalArgumentException(
                    "A coherent family requires one total parent assignment per e-class");
        }

        NavigableMap<ParentRecordKey, TypedEqualityCertificate> ecs = new TreeMap<>();
        NavigableMap<EClassId, TypedEqualityCertificate> pcs = new TreeMap<>();
        NavigableMap<EClassId, List<TypedEqualityCertificate>> scs = new TreeMap<>();
        List<StructuralKey> keyParts = new ArrayList<>();

        for (Map.Entry<EClassId, TypedEClassRecord> entry : records.entrySet()) {
            TypedEClassRecord record = entry.getValue();
            if (!entry.getKey().equals(record.id())) {
                throw new IllegalArgumentException(
                        "A coherent family record is indexed by the wrong e-class id");
            }
            ParentAssignment assignment = assignments.get(entry.getKey());
            TypedEqualityCertificate pc = assignment.provenancePath()
                    .composedCertificate();
            CertificateVerifier.verify(pc);
            TypedCertificateEndpoint expectedParent = TypedCertificateEndpoint.invocation(
                    assignment.parentInvocation());
            if (!pc.leftEndpoint().equals(
                        TypedCertificateEndpoint.eclassWitness(record.interfaceView()))
                    || !pc.rightEndpoint().equals(expectedParent)) {
                throw new IllegalArgumentException(
                        "A coherent-family PC proof has incorrect endpoints");
            }
            pcs.put(entry.getKey(), pc);
            keyParts.add(StructuralKey.branch(
                    "coherent-family/pc",
                    java.util.Arrays.asList(
                            TheoryKeys.eclass(record.interfaceView()),
                            pc.structuralKey())));

            record.symmetryGroup().requireCertifiedFor(record.interfaceView());
            List<TypedEqualityCertificate> classScs = new ArrayList<>();
            for (SymmetryCertificate generator
                    : record.symmetryGroup().generatorCertificates()) {
                TypedEqualityCertificate sc = generator;
                CertificateVerifier.verify(sc);
                classScs.add(sc);
                keyParts.add(StructuralKey.branch(
                        "coherent-family/sc",
                        java.util.Arrays.asList(
                                TheoryKeys.eclass(record.interfaceView()),
                                TheoryKeys.embedding(generator.inducedPermutation()),
                                sc.structuralKey())));
            }
            scs.put(entry.getKey(), Collections.unmodifiableList(classScs));

            for (Map.Entry<CanonicalShape, ShapeWitness> stored
                    : record.shapeWitnesses().entrySet()) {
                ParentRecordKey key = new ParentRecordKey(record.id(), stored.getKey());
                TypedEqualityCertificate supplied = shapeCertificates.get(key);
                if (supplied == null) {
                    throw new IllegalArgumentException(
                            "A coherent family is missing an EC proof for " + key);
                }
                TypedEqualityCertificate ec =
                        EffectiveShapeCollisionCertificate.orientShapeEquation(
                                stored.getKey(), record, stored.getValue(), supplied);
                CertificateVerifier.verify(ec);
                ecs.put(key, ec);
                keyParts.add(StructuralKey.branch(
                        "coherent-family/ec",
                        java.util.Arrays.asList(key.structuralKey(), ec.structuralKey())));
            }
        }
        if (!ecs.keySet().equals(shapeCertificates.keySet())) {
            throw new IllegalArgumentException(
                    "A coherent family contains an EC proof outside the stored shape set");
        }

        this.shapeCoherence = Collections.unmodifiableNavigableMap(ecs);
        this.parentCoherence = Collections.unmodifiableNavigableMap(pcs);
        this.symmetryCoherence = immutableNestedMap(scs);
        this.structuralKey = StructuralKey.branch("coherent-witness-family", keyParts);
    }

    static CoherentWitnessFamily capture(
            TypedSlottedPortEGraph owner,
            long graphRevision,
            Map<EClassId, TypedEClassRecord> records,
            Map<EClassId, ParentAssignment> assignments,
            Map<ParentRecordKey, TypedEqualityCertificate> shapeCertificates) {
        return new CoherentWitnessFamily(
                owner, graphRevision, records, assignments, shapeCertificates);
    }

    private static NavigableMap<EClassId, List<TypedEqualityCertificate>>
            immutableNestedMap(
                    NavigableMap<EClassId, List<TypedEqualityCertificate>> source) {
        NavigableMap<EClassId, List<TypedEqualityCertificate>> result = new TreeMap<>();
        for (Map.Entry<EClassId, List<TypedEqualityCertificate>> entry
                : source.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(
                    new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableNavigableMap(result);
    }

    void requireCurrent(TypedSlottedPortEGraph graph, long currentRevision) {
        if (owner != Objects.requireNonNull(graph, "graph")) {
            throw new IllegalArgumentException(
                    "A coherent witness family belongs to a different graph");
        }
        if (graphRevision != currentRevision) {
            throw new IllegalStateException(
                    "A coherent witness family is stale after a semantic graph mutation");
        }
    }

    public long graphRevision() {
        return graphRevision;
    }

    CanonicalShape canonicalShapeOf(TypedENode node) {
        owner.requireCurrentWitnessFamily(this);
        return owner.canonicalizeStoredNode(
                Objects.requireNonNull(node, "node").inExactSupportContext()).shape();
    }

    /** EC proofs indexed by their exact owner and canonical shape. */
    public Map<ParentRecordKey, TypedEqualityCertificate> shapeCoherence() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(shapeCoherence));
    }

    /** PC proof from each witness to its current parent invocation. */
    public Map<EClassId, TypedEqualityCertificate> parentCoherence() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(parentCoherence));
    }

    /** SC reconstruction proofs, in the owning group's deterministic order. */
    public Map<EClassId, List<TypedEqualityCertificate>> symmetryCoherence() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(symmetryCoherence));
    }

    TypedEqualityCertificate parentCoherence(EClassId id) {
        TypedEqualityCertificate result = parentCoherence.get(
                Objects.requireNonNull(id, "id"));
        if (result == null) {
            throw new IllegalArgumentException("Unknown coherent-family e-class: " + id);
        }
        return result;
    }

    List<TypedEqualityCertificate> symmetryCoherence(EClassId id) {
        List<TypedEqualityCertificate> result = symmetryCoherence.get(
                Objects.requireNonNull(id, "id"));
        if (result == null) {
            throw new IllegalArgumentException("Unknown coherent-family e-class: " + id);
        }
        return result;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public String toString() {
        return "coherent-witness-family[EC=" + shapeCoherence.size()
                + ", PC=" + parentCoherence.size()
                + ", SC=" + symmetryCoherence.size() + "]";
    }
}
