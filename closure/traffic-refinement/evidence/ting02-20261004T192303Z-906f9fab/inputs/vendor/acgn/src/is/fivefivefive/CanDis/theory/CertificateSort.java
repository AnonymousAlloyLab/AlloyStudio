package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Objects;

/** Sort carried by both endpoints of a typed equality certificate. */
public final class CertificateSort {
    public enum Kind {
        TERM,
        PORT,
        BINDER_LAW
    }

    private final Kind kind;
    private final GraphType termType;
    private final PortSchema portSchema;
    private final StructuralKey binderDescriptorKey;
    private final StructuralKey structuralKey;

    private CertificateSort(
            Kind kind,
            GraphType termType,
            PortSchema portSchema,
            StructuralKey binderDescriptorKey) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.termType = termType;
        this.portSchema = portSchema;
        this.binderDescriptorKey = binderDescriptorKey;
        int payloads = (termType == null ? 0 : 1)
                + (portSchema == null ? 0 : 1)
                + (binderDescriptorKey == null ? 0 : 1);
        if (payloads != 1
                || (kind == Kind.TERM) != (termType != null)
                || (kind == Kind.PORT) != (portSchema != null)
                || (kind == Kind.BINDER_LAW) != (binderDescriptorKey != null)) {
            throw new IllegalArgumentException("Certificate sort has inconsistent payload");
        }
        StructuralKey payload = termType != null
                ? TheoryKeys.type(termType)
                : portSchema != null ? portSchema.structuralKey() : binderDescriptorKey;
        this.structuralKey = StructuralKey.of(
                "certificate-sort",
                Collections.singletonList(kind.name()),
                Collections.singletonList(payload));
    }

    public static CertificateSort term(GraphType type) {
        return new CertificateSort(
                Kind.TERM, Objects.requireNonNull(type, "type"), null, null);
    }

    public static CertificateSort port(PortSchema schema) {
        return new CertificateSort(
                Kind.PORT, null, Objects.requireNonNull(schema, "schema"), null);
    }

    static CertificateSort binderLaw(StructuralKey descriptorKey) {
        return new CertificateSort(
                Kind.BINDER_LAW,
                null,
                null,
                Objects.requireNonNull(descriptorKey, "descriptorKey"));
    }

    public Kind kind() {
        return kind;
    }

    public GraphType termType() {
        if (termType == null) {
            throw new IllegalStateException("Certificate sort is not a term sort");
        }
        return termType;
    }

    public PortSchema portSchema() {
        if (portSchema == null) {
            throw new IllegalStateException("Certificate sort is not a port sort");
        }
        return portSchema;
    }

    StructuralKey binderDescriptorKey() {
        if (binderDescriptorKey == null) {
            throw new IllegalStateException("Certificate sort is not a binder-law sort");
        }
        return binderDescriptorKey;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CertificateSort)) {
            return false;
        }
        CertificateSort sort = (CertificateSort) other;
        return kind == sort.kind
                && Objects.equals(termType, sort.termType)
                && Objects.equals(portSchema, sort.portSchema)
                && Objects.equals(binderDescriptorKey, sort.binderDescriptorKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, termType, portSchema, binderDescriptorKey);
    }

    @Override
    public String toString() {
        return structuralKey.toString();
    }
}
