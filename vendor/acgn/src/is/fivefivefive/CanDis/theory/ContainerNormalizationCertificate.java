package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Concrete replay of one Seq/Bag/Set normalization trace. */
public final class ContainerNormalizationCertificate extends TypedEqualityCertificate {
    private final PortValue source;
    private final PortValue normalized;
    private final ContainerNormalizationTrace trace;
    private final InstantiatedOperator operator;
    private final PortPath path;
    private final ContainerLawDeclaration declaration;
    private final List<TypedEqualityCertificate> childCertificates;
    private final boolean productionAuthorityRequired;

    private ContainerNormalizationCertificate(Build build) {
        super(
                CertificateCategory.CONTAINER_NORMALIZATION,
                TypedCertificateEndpoint.port(build.source),
                TypedCertificateEndpoint.port(build.normalized),
                build.premises,
                java.util.Arrays.asList(
                        build.trace.structuralKey(),
                        build.operator.structuralKey(),
                        StructuralKey.leaf("port-path", build.path.toString()),
                        StructuralKey.leaf(
                                "production-authority-required",
                                Boolean.toString(build.productionAuthorityRequired)),
                        build.declaration.structuralKey()));
        this.source = build.source;
        this.normalized = build.normalized;
        this.trace = build.trace;
        this.operator = build.operator;
        this.path = build.path;
        this.declaration = build.declaration;
        this.childCertificates = build.children;
        this.productionAuthorityRequired = build.productionAuthorityRequired;
        verifyLocal();
    }

    static TypedEqualityCertificate create(
            PortValue source,
            PortValue normalized,
            ContainerNormalizationTrace trace,
            InstantiatedOperator operator,
            PortPath path,
            List<? extends TypedEqualityCertificate> childCertificates,
            boolean productionAuthorityRequired) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(normalized, "normalized");
        if (source.equals(normalized)) {
            return EqualityCertificates.reflexive(
                    TypedCertificateEndpoint.port(source));
        }
        ContainerNormalizationCertificate result =
                new ContainerNormalizationCertificate(build(
                        source,
                        normalized,
                        trace,
                        operator,
                        path,
                        childCertificates,
                        productionAuthorityRequired));
        CertificateVerifier.verify(result);
        return result;
    }

    private static Build build(
            PortValue source,
            PortValue normalized,
            ContainerNormalizationTrace trace,
            InstantiatedOperator operator,
            PortPath path,
            List<? extends TypedEqualityCertificate> suppliedChildren,
            boolean productionAuthorityRequired) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(path, "path");
        ContainerLawDeclaration declaration = operator.lawForPath(path);
        if (!operator.schemaAt(path).equals(source.schema())) {
            throw new IllegalArgumentException(
                    "Container replay schema does not match its operator path");
        }
        declaration.validateAgainst(source.schema());
        declaration.requireCertified();
        declaration.validateEvidenceFor(
                operator.operator(),
                operator.outputType(),
                path,
                source.schema(),
                productionAuthorityRequired);
        if (!source.getClass().equals(normalized.getClass())
                || !source.schema().equals(normalized.schema())
                || !source.context().equals(normalized.context())) {
            throw new IllegalArgumentException(
                    "Container replay must preserve constructor, schema, and context");
        }

        List<PortValue> sourceOccurrences = occurrences(source);
        Objects.requireNonNull(suppliedChildren, "childCertificates");
        if (sourceOccurrences.size() != suppliedChildren.size()
                || sourceOccurrences.size() != trace.inputOccurrences().size()) {
            throw new IllegalArgumentException(
                    "Container replay requires one proof per source occurrence");
        }
        List<TypedEqualityCertificate> children = new ArrayList<>(
                suppliedChildren.size());
        for (int index = 0; index < sourceOccurrences.size(); index++) {
            TypedEqualityCertificate supplied = Objects.requireNonNull(
                    suppliedChildren.get(index), "child certificate");
            CertificateVerifier.verify(supplied);
            children.add(EqualityCertificates.orient(
                    supplied,
                    TypedCertificateEndpoint.port(sourceOccurrences.get(index)),
                    TypedCertificateEndpoint.port(trace.inputOccurrences().get(index))));
        }

        ContainerNormalizationTrace expected = ContainerNormalizationTrace.of(
                source, trace.inputOccurrences(), normalized);
        if (!expected.equals(trace)) {
            throw new IllegalArgumentException(
                    "Container certificate does not replay the supplied structural trace");
        }
        requireApplicableLaws(trace, declaration);

        List<TypedEqualityCertificate> premises = new ArrayList<>(children);
        premises.addAll(declaration.certificates().values());
        return new Build(
                source,
                normalized,
                trace,
                operator,
                path,
                declaration,
                Collections.unmodifiableList(children),
                productionAuthorityRequired,
                Collections.unmodifiableList(premises));
    }

    private static void requireApplicableLaws(
            ContainerNormalizationTrace trace,
            ContainerLawDeclaration declaration) {
        if (trace.kind() == PortSchema.Kind.SEQ && trace.reordered()) {
            throw new IllegalArgumentException(
                    "Sequence normalization cannot reorder or merge occurrences");
        }
        if ((trace.kind() == PortSchema.Kind.BAG
                    || trace.kind() == PortSchema.Kind.SET)
                && trace.reordered()
                && !declaration.commutative()) {
            throw new IllegalArgumentException(
                    "An order-changing container replay requires commutativity");
        }
        if (trace.deduplicated() && !declaration.idempotent()) {
            throw new IllegalArgumentException(
                    "A duplicate-merging container replay requires idempotency");
        }
    }

    private static List<PortValue> occurrences(PortValue container) {
        if (container instanceof SeqPort) {
            return ((SeqPort) container).elements();
        }
        if (container instanceof BagPort) {
            return ((BagPort) container).occurrences();
        }
        if (container instanceof SetPort) {
            return ((SetPort) container).elements();
        }
        throw new IllegalArgumentException("A normalization certificate requires a container");
    }

    public ContainerNormalizationTrace trace() {
        return trace;
    }

    public List<TypedEqualityCertificate> childCertificates() {
        return childCertificates;
    }

    @Override
    void verifyLocal() {
        if (source.equals(normalized)
                || !leftEndpoint().equals(TypedCertificateEndpoint.port(source))
                || !rightEndpoint().equals(TypedCertificateEndpoint.port(normalized))) {
            throw new IllegalStateException(
                    "Malformed concrete container-normalization certificate");
        }
        declaration.requireCertified();
        if (!operator.lawForPath(path).equals(declaration)
                || !operator.schemaAt(path).equals(source.schema())) {
            throw new IllegalStateException(
                    "Container normalization lost its exact operator/path binding");
        }
        declaration.validateEvidenceFor(
                operator.operator(),
                operator.outputType(),
                path,
                source.schema(),
                productionAuthorityRequired);
        requireApplicableLaws(trace, declaration);
    }

    private static final class Build {
        private final PortValue source;
        private final PortValue normalized;
        private final ContainerNormalizationTrace trace;
        private final InstantiatedOperator operator;
        private final PortPath path;
        private final ContainerLawDeclaration declaration;
        private final List<TypedEqualityCertificate> children;
        private final boolean productionAuthorityRequired;
        private final List<TypedEqualityCertificate> premises;

        private Build(
                PortValue source,
                PortValue normalized,
                ContainerNormalizationTrace trace,
                InstantiatedOperator operator,
                PortPath path,
                ContainerLawDeclaration declaration,
                List<TypedEqualityCertificate> children,
                boolean productionAuthorityRequired,
                List<TypedEqualityCertificate> premises) {
            this.source = source;
            this.normalized = normalized;
            this.trace = trace;
            this.operator = operator;
            this.path = path;
            this.declaration = declaration;
            this.children = children;
            this.productionAuthorityRequired = productionAuthorityRequired;
            this.premises = premises;
        }
    }
}
