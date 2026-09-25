package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Concrete C/I replay from ordered source occurrences to one nonflat container node. */
public final class ContainerConstructionCertificate extends TypedEqualityCertificate {
    private final InstantiatedOperator operator;
    private final PortPath path;
    private final TypedSlotContext context;
    private final List<PortValue> inputOccurrences;
    private final TypedENode target;
    private final SemanticProfile semanticProfile;
    private final ContainerLawDeclaration declaration;
    private final ContainerApplicationTrace containerTrace;

    private ContainerConstructionCertificate(Build build) {
        super(
                CertificateCategory.CONTAINER_NORMALIZATION,
                TypedCertificateEndpoint.containerApplication(
                        build.operator,
                        build.path,
                        build.context,
                        build.inputOccurrences,
                        build.semanticProfile),
                TypedCertificateEndpoint.node(build.target),
                build.premises,
                build.details);
        this.operator = build.operator;
        this.path = build.path;
        this.context = build.context;
        this.inputOccurrences = build.inputOccurrences;
        this.target = build.target;
        this.semanticProfile = build.semanticProfile;
        this.declaration = build.declaration;
        this.containerTrace = build.containerTrace;
        verifyLocal();
    }

    static ContainerConstructionCertificate createProduction(
            InstantiatedOperator operator,
            PortPath path,
            TypedSlotContext context,
            List<? extends PortValue> inputOccurrences,
            TypedENode target,
            SemanticProfile semanticProfile) {
        ContainerConstructionCertificate certificate =
                new ContainerConstructionCertificate(build(
                        operator,
                        path,
                        context,
                        inputOccurrences,
                        target,
                        semanticProfile));
        CertificateVerifier.verify(certificate);
        return certificate;
    }

    private static Build build(
            InstantiatedOperator operator,
            PortPath path,
            TypedSlotContext context,
            List<? extends PortValue> inputOccurrences,
            TypedENode target,
            SemanticProfile semanticProfile) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(inputOccurrences, "inputOccurrences");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        if (!semanticProfile.isAdmissibleAlloyProfile()) {
            throw new IllegalArgumentException(
                    "Production container normalization requires an authorized Alloy profile");
        }
        if (operator.usesFlatConstruction()) {
            throw new IllegalArgumentException(
                    "Flat operators require a flat-construction certificate");
        }
        if (path.depth() != 0
                || !operator.equals(target.operator())
                || !context.equals(target.context())
                || path.portIndex() >= target.ports().size()) {
            throw new IllegalArgumentException(
                    "Container source and target do not share one exact root operator path");
        }
        PortSchema schema = operator.schemaAt(path);
        if (!(schema instanceof SeqPortSchema)
                && !(schema instanceof BagPortSchema)
                && !(schema instanceof SetPortSchema)) {
            throw new IllegalArgumentException(
                    "A concrete container replay requires a container schema");
        }
        ContainerLawDeclaration declaration = operator.lawForPath(path);
        declaration.requireCertified();
        declaration.validateEvidenceFor(
                operator.operator(), operator.outputType(), path, schema, false);
        for (ContainerLawCertificate certificate : declaration.certificates().values()) {
            if (!semanticProfile.equals(certificate.semanticProfile())) {
                throw new IllegalArgumentException(
                        "Container application law uses another semantic profile");
            }
        }

        List<PortValue> copiedInputs = new ArrayList<>(inputOccurrences.size());
        for (PortValue occurrence : inputOccurrences) {
            copiedInputs.add(Objects.requireNonNull(occurrence, "input occurrence"));
        }
        copiedInputs = Collections.unmodifiableList(copiedInputs);
        ContainerApplicationTrace trace = ContainerApplicationTrace.of(
                schema, context, copiedInputs, target.ports().get(path.portIndex()));
        List<TypedEqualityCertificate> premises = new ArrayList<>();
        boolean commutativeQuotient = (schema instanceof BagPortSchema
                        || schema instanceof SetPortSchema)
                && copiedInputs.size() > 1;
        if (commutativeQuotient) {
            premises.add(requireLaw(
                    declaration, ContainerLawCertificate.Law.COMMUTATIVITY));
        }
        if (trace.deduplicated()) {
            premises.add(requireLaw(
                    declaration, ContainerLawCertificate.Law.IDEMPOTENCY));
        }

        List<StructuralKey> details = new ArrayList<>();
        details.add(semanticProfile.structuralKey());
        details.add(operator.structuralKey());
        details.add(StructuralKey.leaf("port-path", path.toString()));
        details.add(target.structuralKey());
        details.add(trace.structuralKey());
        return new Build(
                operator,
                path,
                context,
                copiedInputs,
                target,
                semanticProfile,
                declaration,
                trace,
                Collections.unmodifiableList(premises),
                Collections.unmodifiableList(details));
    }

    private static ContainerLawCertificate requireLaw(
            ContainerLawDeclaration declaration,
            ContainerLawCertificate.Law law) {
        ContainerLawCertificate certificate = declaration.certificates().get(law);
        if (certificate == null) {
            throw new IllegalArgumentException(
                    "Concrete container replay requires a declared " + law + " law");
        }
        return certificate;
    }

    public InstantiatedOperator operator() { return operator; }
    public PortPath path() { return path; }
    public List<PortValue> inputOccurrences() { return inputOccurrences; }
    public TypedENode target() { return target; }
    public SemanticProfile semanticProfile() { return semanticProfile; }
    public ContainerApplicationTrace containerTrace() { return containerTrace; }

    @Override
    void verifyLocal() {
        Build rebuilt = build(
                operator,
                path,
                context,
                inputOccurrences,
                target,
                semanticProfile);
        if (!declaration.equals(rebuilt.declaration)
                || !containerTrace.structuralKey().equals(
                        rebuilt.containerTrace.structuralKey())
                || !leftEndpoint().equals(TypedCertificateEndpoint.containerApplication(
                        operator, path, context, inputOccurrences, semanticProfile))
                || !rightEndpoint().equals(TypedCertificateEndpoint.node(target))) {
            throw new IllegalStateException(
                    "Malformed concrete container-construction certificate");
        }
    }

    private static final class Build {
        private final InstantiatedOperator operator;
        private final PortPath path;
        private final TypedSlotContext context;
        private final List<PortValue> inputOccurrences;
        private final TypedENode target;
        private final SemanticProfile semanticProfile;
        private final ContainerLawDeclaration declaration;
        private final ContainerApplicationTrace containerTrace;
        private final List<TypedEqualityCertificate> premises;
        private final List<StructuralKey> details;

        private Build(
                InstantiatedOperator operator,
                PortPath path,
                TypedSlotContext context,
                List<PortValue> inputOccurrences,
                TypedENode target,
                SemanticProfile semanticProfile,
                ContainerLawDeclaration declaration,
                ContainerApplicationTrace containerTrace,
                List<TypedEqualityCertificate> premises,
                List<StructuralKey> details) {
            this.operator = operator;
            this.path = path;
            this.context = context;
            this.inputOccurrences = inputOccurrences;
            this.target = target;
            this.semanticProfile = semanticProfile;
            this.declaration = declaration;
            this.containerTrace = containerTrace;
            this.premises = premises;
            this.details = details;
        }
    }
}
