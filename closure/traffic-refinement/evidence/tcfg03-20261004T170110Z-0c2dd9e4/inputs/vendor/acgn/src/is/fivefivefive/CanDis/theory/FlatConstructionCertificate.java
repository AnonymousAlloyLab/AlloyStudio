package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Concrete A/C/I replay from visible nested syntax to one flat typed e-node. */
public final class FlatConstructionCertificate extends TypedEqualityCertificate {
    public static final class Splice {
        private final List<Integer> path;
        private final int outerArity;
        private final int nestedArity;
        private final int position;
        private final StructuralKey nestedSource;
        private final StructuralKey structuralKey;

        private Splice(
                List<Integer> path,
                int outerArity,
                int nestedArity,
                int position,
                StructuralKey nestedSource) {
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
            this.outerArity = outerArity;
            this.nestedArity = nestedArity;
            this.position = position;
            this.nestedSource = Objects.requireNonNull(nestedSource, "nestedSource");
            List<String> coordinates = new ArrayList<>();
            for (Integer coordinate : path) {
                coordinates.add(Integer.toString(coordinate));
            }
            coordinates.add(Integer.toString(outerArity));
            coordinates.add(Integer.toString(nestedArity));
            coordinates.add(Integer.toString(position));
            this.structuralKey = StructuralKey.of(
                    "associative-splice-v1",
                    coordinates,
                    Collections.singletonList(nestedSource));
        }

        public List<Integer> path() { return path; }
        public int outerArity() { return outerArity; }
        public int nestedArity() { return nestedArity; }
        public int position() { return position; }
        public StructuralKey nestedSource() { return nestedSource; }
        public StructuralKey structuralKey() { return structuralKey; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Splice
                    && structuralKey.equals(((Splice) other).structuralKey);
        }

        @Override
        public int hashCode() {
            return structuralKey.hashCode();
        }
    }

    private final FlatApplication source;
    private final TypedENode target;
    private final OnePort singletonTarget;
    private final SemanticProfile semanticProfile;
    private final PortPath path;
    private final ContainerLawDeclaration declaration;
    private final List<Splice> splices;
    private final ContainerApplicationTrace containerTrace;

    private FlatConstructionCertificate(Build build) {
        super(
                CertificateCategory.CONTAINER_NORMALIZATION,
                TypedCertificateEndpoint.flatApplication(
                        build.source, build.semanticProfile),
                build.targetEndpoint,
                build.premises,
                build.details);
        this.source = build.source;
        this.target = build.target;
        this.singletonTarget = build.singletonTarget;
        this.semanticProfile = build.semanticProfile;
        this.path = build.path;
        this.declaration = build.declaration;
        this.splices = build.splices;
        this.containerTrace = build.containerTrace;
        verifyLocal();
    }

    static FlatConstructionCertificate createProduction(
            FlatApplication source,
            TypedENode target,
            SemanticProfile semanticProfile) {
        FlatConstructionCertificate certificate = new FlatConstructionCertificate(
                build(source, target, null, semanticProfile));
        CertificateVerifier.verify(certificate);
        return certificate;
    }

    static FlatConstructionCertificate createSingletonProduction(
            FlatApplication source,
            OnePort singletonTarget,
            SemanticProfile semanticProfile) {
        FlatConstructionCertificate certificate = new FlatConstructionCertificate(
                build(source, null, singletonTarget, semanticProfile));
        CertificateVerifier.verify(certificate);
        return certificate;
    }

    private static Build build(
            FlatApplication source,
            TypedENode target,
            OnePort singletonTarget,
            SemanticProfile semanticProfile) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        if ((target == null) == (singletonTarget == null)) {
            throw new IllegalArgumentException(
                    "A flat construction has exactly one node or singleton target");
        }
        if (!semanticProfile.isAdmissibleAlloyProfile()) {
            throw new IllegalArgumentException(
                    "Production flattening requires an authorized Alloy profile");
        }
        InstantiatedOperator operator = source.operator();
        PortPath path = operator.flatLicense().path();
        if ((target != null && (!operator.equals(target.operator())
                    || !source.context().equals(target.context())
                    || target.ports().size() != 1))
                || (singletonTarget != null
                    && !source.context().equals(singletonTarget.context()))
                || !PortPath.at(0).equals(path)) {
            throw new IllegalArgumentException(
                    "Flat source and target do not share one exact root operator");
        }
        ContainerLawDeclaration declaration = operator.lawForPath(path);
        PortSchema schema = operator.schemaAt(path);
        declaration.requireCertified();
        declaration.validateEvidenceFor(
                operator.operator(), operator.outputType(), path, schema, true);
        for (ContainerLawCertificate certificate : declaration.certificates().values()) {
            if (!semanticProfile.equals(certificate.semanticProfile())) {
                throw new IllegalArgumentException(
                        "Flat application law uses another semantic profile");
            }
        }

        List<FlatLeaf> leaves = new ArrayList<>();
        List<Splice> splices = new ArrayList<>();
        collect(source, operator, new ArrayList<>(), leaves, splices);
        List<PortValue> occurrences = new ArrayList<>(leaves.size());
        for (FlatLeaf leaf : leaves) {
            occurrences.add(leaf.port());
        }
        PortValue targetContainer;
        TypedCertificateEndpoint targetEndpoint;
        if (target != null) {
            targetContainer = target.ports().get(path.portIndex());
            targetEndpoint = TypedCertificateEndpoint.node(target);
        } else {
            if (!(schema instanceof SetPortSchema)
                    || !singletonTarget.schema().equals(
                            OperatorDeclaration.elementSchema(schema))) {
                throw new IllegalArgumentException(
                        "Only an idempotent Set flat operator may collapse to one operand");
            }
            targetContainer = new SetPort(
                    (SetPortSchema) schema,
                    source.context(),
                    Collections.singletonList(singletonTarget));
            targetEndpoint = TypedCertificateEndpoint.oneTerm(singletonTarget);
        }
        ContainerApplicationTrace containerTrace = ContainerApplicationTrace.of(
                schema, source.context(), occurrences, targetContainer);
        if (singletonTarget != null
                && !containerTrace.deduplicated()
                && occurrences.size() != 1) {
            throw new IllegalArgumentException(
                    "A singleton flat collapse requires one source operand or an idempotent quotient");
        }

        List<TypedEqualityCertificate> premises = new ArrayList<>();
        if (!splices.isEmpty()) {
            premises.add(requireLaw(
                    declaration, ContainerLawCertificate.Law.ASSOCIATIVITY));
        }
        if (containerTrace.reordered()) {
            premises.add(requireLaw(
                    declaration, ContainerLawCertificate.Law.COMMUTATIVITY));
        }
        if (containerTrace.deduplicated()) {
            premises.add(requireLaw(
                    declaration, ContainerLawCertificate.Law.IDEMPOTENCY));
        }

        List<StructuralKey> details = new ArrayList<>();
        details.add(semanticProfile.structuralKey());
        details.add(operator.structuralKey());
        details.add(StructuralKey.leaf("port-path", path.toString()));
        details.add(source.structuralKey());
        details.add(target == null
                ? singletonTarget.structuralKey()
                : target.structuralKey());
        details.add(containerTrace.structuralKey());
        for (Splice splice : splices) {
            details.add(splice.structuralKey());
        }
        return new Build(
                source,
                target,
                singletonTarget,
                targetEndpoint,
                semanticProfile,
                path,
                declaration,
                Collections.unmodifiableList(splices),
                containerTrace,
                Collections.unmodifiableList(premises),
                Collections.unmodifiableList(details));
    }

    private static ContainerLawCertificate requireLaw(
            ContainerLawDeclaration declaration,
            ContainerLawCertificate.Law law) {
        ContainerLawCertificate certificate = declaration.certificates().get(law);
        if (certificate == null) {
            throw new IllegalArgumentException(
                    "Concrete flat replay requires a declared " + law + " law");
        }
        return certificate;
    }

    private static void collect(
            FlatApplication application,
            InstantiatedOperator operator,
            List<Integer> path,
            List<FlatLeaf> leaves,
            List<Splice> splices) {
        if (!operator.equals(application.operator())) {
            throw new IllegalArgumentException(
                    "A concrete flat replay cannot cross operator instances");
        }
        for (int position = 0; position < application.operands().size(); position++) {
            FlatInput input = application.operands().get(position);
            if (input instanceof FlatLeaf) {
                leaves.add((FlatLeaf) input);
                continue;
            }
            if (!(input instanceof FlatApplication)) {
                throw new IllegalStateException("Unknown flat input implementation");
            }
            FlatApplication nested = (FlatApplication) input;
            if (!operator.equals(nested.operator())) {
                throw new IllegalArgumentException(
                        "Different-headed nested applications must be sealed before certification");
            }
            List<Integer> nestedPath = new ArrayList<>(path);
            nestedPath.add(position);
            splices.add(new Splice(
                    nestedPath,
                    application.operands().size(),
                    nested.operands().size(),
                    position,
                    nested.structuralKey()));
            collect(nested, operator, nestedPath, leaves, splices);
        }
    }

    public FlatApplication source() { return source; }
    public boolean collapsedToSingleton() { return singletonTarget != null; }
    public TypedENode target() {
        if (target == null) {
            throw new IllegalStateException(
                    "This flat construction collapses directly to its sole operand");
        }
        return target;
    }
    public OnePort singletonTarget() {
        if (singletonTarget == null) {
            throw new IllegalStateException(
                    "This flat construction retains an operator node");
        }
        return singletonTarget;
    }
    public SemanticProfile semanticProfile() { return semanticProfile; }
    public PortPath path() { return path; }
    public List<Splice> splices() { return splices; }
    public ContainerApplicationTrace containerTrace() { return containerTrace; }

    @Override
    void verifyLocal() {
        Build rebuilt = build(source, target, singletonTarget, semanticProfile);
        if (!path.equals(rebuilt.path)
                || !declaration.equals(rebuilt.declaration)
                || !splices.equals(rebuilt.splices)
                || !containerTrace.structuralKey().equals(
                        rebuilt.containerTrace.structuralKey())
                || !leftEndpoint().equals(TypedCertificateEndpoint.flatApplication(
                        source, semanticProfile))
                || !rightEndpoint().equals(rebuilt.targetEndpoint)) {
            throw new IllegalStateException("Malformed concrete flat-construction certificate");
        }
    }

    private static final class Build {
        private final FlatApplication source;
        private final TypedENode target;
        private final OnePort singletonTarget;
        private final TypedCertificateEndpoint targetEndpoint;
        private final SemanticProfile semanticProfile;
        private final PortPath path;
        private final ContainerLawDeclaration declaration;
        private final List<Splice> splices;
        private final ContainerApplicationTrace containerTrace;
        private final List<TypedEqualityCertificate> premises;
        private final List<StructuralKey> details;

        private Build(
                FlatApplication source,
                TypedENode target,
                OnePort singletonTarget,
                TypedCertificateEndpoint targetEndpoint,
                SemanticProfile semanticProfile,
                PortPath path,
                ContainerLawDeclaration declaration,
                List<Splice> splices,
                ContainerApplicationTrace containerTrace,
                List<TypedEqualityCertificate> premises,
                List<StructuralKey> details) {
            this.source = source;
            this.target = target;
            this.singletonTarget = singletonTarget;
            this.targetEndpoint = targetEndpoint;
            this.semanticProfile = semanticProfile;
            this.path = path;
            this.declaration = declaration;
            this.splices = splices;
            this.containerTrace = containerTrace;
            this.premises = premises;
            this.details = details;
        }
    }
}
