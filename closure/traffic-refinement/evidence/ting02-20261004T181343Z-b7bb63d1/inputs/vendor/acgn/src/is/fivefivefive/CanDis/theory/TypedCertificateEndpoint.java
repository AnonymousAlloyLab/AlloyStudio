package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A machine-checkable context, sort, and expression endpoint for a certificate. */
public final class TypedCertificateEndpoint {
    enum Kind {
        ECLASS_WITNESS,
        INVOCATION,
        NODE,
        PORT,
        ONE_TERM,
        FLAT_APPLICATION,
        DEPENDENT_CHAIN_APPLICATION,
        CONTAINER_APPLICATION,
        CONTAINER_PATTERN,
        BINDER_PATTERN,
        RENAMED
    }

    private final Kind kind;
    private final TypedSlotContext context;
    private final CertificateSort sort;
    private final Object payload;
    private final StructuralKey expressionKey;
    private final StructuralKey structuralKey;

    private TypedCertificateEndpoint(
            Kind kind,
            TypedSlotContext context,
            CertificateSort sort,
            Object payload,
            StructuralKey expressionKey) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.context = Objects.requireNonNull(context, "context");
        this.sort = Objects.requireNonNull(sort, "sort");
        this.payload = payload;
        this.expressionKey = Objects.requireNonNull(expressionKey, "expressionKey");
        this.structuralKey = StructuralKey.of(
                "certificate-endpoint",
                Collections.singletonList(kind.name()),
                Arrays.asList(
                        TheoryKeys.context(context),
                        sort.structuralKey(),
                        expressionKey));
    }

    public static TypedCertificateEndpoint eclassWitness(
            TypedEClassInterface eclass) {
        Objects.requireNonNull(eclass, "eclass");
        return new TypedCertificateEndpoint(
                Kind.ECLASS_WITNESS,
                eclass.exposedSlots(),
                CertificateSort.term(eclass.outputType()),
                eclass,
                StructuralKey.branch(
                        "certificate-term/eclass-witness",
                        Collections.singletonList(TheoryKeys.eclass(eclass))));
    }

    public static TypedCertificateEndpoint invocation(TypedInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        if (invocation.embedding().equals(
                TypedEmbedding.identity(invocation.eclass().exposedSlots()))) {
            return eclassWitness(invocation.eclass());
        }
        return new TypedCertificateEndpoint(
                Kind.INVOCATION,
                invocation.callerContext(),
                CertificateSort.term(invocation.outputType()),
                invocation,
                StructuralKey.branch(
                        "certificate-term/invocation",
                        Collections.singletonList(TheoryKeys.invocation(invocation))));
    }

    public static TypedCertificateEndpoint node(TypedENode node) {
        Objects.requireNonNull(node, "node");
        return new TypedCertificateEndpoint(
                Kind.NODE,
                node.context(),
                CertificateSort.term(node.outputType()),
                node,
                StructuralKey.branch(
                        "certificate-term/node",
                        Collections.singletonList(node.structuralKey())));
    }

    public static TypedCertificateEndpoint port(PortValue port) {
        Objects.requireNonNull(port, "port");
        return new TypedCertificateEndpoint(
                Kind.PORT,
                port.context(),
                CertificateSort.port(port.schema()),
                port,
                StructuralKey.branch(
                        "certificate-term/port",
                        Collections.singletonList(port.structuralKey())));
    }

    /** Interprets a One-port leaf as the term it denotes, not as port syntax. */
    static TypedCertificateEndpoint oneTerm(OnePort port) {
        Objects.requireNonNull(port, "port");
        return new TypedCertificateEndpoint(
                Kind.ONE_TERM,
                port.context(),
                CertificateSort.term(port.schema().type()),
                port,
                StructuralKey.branch(
                        "certificate-term/one-port",
                        Collections.singletonList(port.structuralKey())));
    }

    static TypedCertificateEndpoint flatApplication(
            FlatApplication application,
            SemanticProfile semanticProfile) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        return new TypedCertificateEndpoint(
                Kind.FLAT_APPLICATION,
                application.context(),
                CertificateSort.term(application.outputType()),
                application,
                StructuralKey.of(
                        "certificate-term/flat-application",
                        Collections.emptyList(),
                        Arrays.asList(
                                semanticProfile.structuralKey(),
                                application.structuralKey())));
    }

    static TypedCertificateEndpoint containerApplication(
            InstantiatedOperator operator,
            PortPath path,
            TypedSlotContext context,
            List<? extends PortValue> inputOccurrences,
            SemanticProfile semanticProfile) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(inputOccurrences, "inputOccurrences");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        List<StructuralKey> children = new ArrayList<>(inputOccurrences.size() + 3);
        children.add(semanticProfile.structuralKey());
        children.add(operator.structuralKey());
        children.add(StructuralKey.leaf("port-path", path.toString()));
        for (int index = 0; index < inputOccurrences.size(); index++) {
            PortValue occurrence = Objects.requireNonNull(
                    inputOccurrences.get(index), "input occurrence");
            if (!context.equals(occurrence.context())) {
                throw new IllegalArgumentException(
                        "Container source occurrence uses another caller context");
            }
            children.add(StructuralKey.of(
                    "container-source-occurrence",
                    Collections.singletonList(Integer.toString(index)),
                    Collections.singletonList(occurrence.structuralKey())));
        }
        return new TypedCertificateEndpoint(
                Kind.CONTAINER_APPLICATION,
                context,
                CertificateSort.term(operator.outputType()),
                null,
                StructuralKey.branch("certificate-term/container-application", children));
    }

    static TypedCertificateEndpoint dependentChainApplication(
            DependentChainApplication application,
            SemanticProfile semanticProfile) {
        return dependentChainApplication(
                application,
                semanticProfile,
                StructuralKey.branch(
                        "dependent-chain-semantic-source-v1",
                        Collections.singletonList(application.structuralKey())));
    }

    static TypedCertificateEndpoint dependentChainApplication(
            DependentChainApplication application,
            SemanticProfile semanticProfile,
            StructuralKey sourceOccurrenceCommitment) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        Objects.requireNonNull(
                sourceOccurrenceCommitment, "sourceOccurrenceCommitment");
        return new TypedCertificateEndpoint(
                Kind.DEPENDENT_CHAIN_APPLICATION,
                application.context(),
                CertificateSort.term(application.outputType()),
                application,
                StructuralKey.of(
                        "certificate-term/dependent-chain-application-v1",
                        Collections.emptyList(),
                        Arrays.asList(
                                semanticProfile.structuralKey(),
                                application.structuralKey(),
                                sourceOccurrenceCommitment)));
    }

    public static TypedCertificateEndpoint restrictedWitness(
            TypedEClassInterface original,
            TypedSlotContext restrictedContext) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(restrictedContext, "restrictedContext");
        if (!restrictedContext.isSubcontextOf(original.exposedSlots())) {
            throw new IllegalArgumentException(
                    "A restricted witness context must be a typed subcontext");
        }
        return eclassWitness(new TypedEClassInterface(
                original.id(), original.outputType(), restrictedContext));
    }

    static TypedCertificateEndpoint containerPattern(
            PortSchema schema,
            ContainerLawCertificate.Law law,
            String side) {
        return containerPattern(
                schema,
                law,
                side,
                StructuralKey.leaf("container-law-index", "legacy-test-only"));
    }

    static TypedCertificateEndpoint containerPattern(
            PortSchema schema,
            ContainerLawCertificate.Law law,
            String side,
            StructuralKey lawIndex) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(law, "law");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(lawIndex, "lawIndex");
        return new TypedCertificateEndpoint(
                Kind.CONTAINER_PATTERN,
                TypedSlotContext.empty(),
                CertificateSort.port(schema),
                null,
                StructuralKey.of(
                        "certificate-pattern/container-law",
                        Arrays.asList(law.name(), side),
                        Arrays.asList(schema.structuralKey(), lawIndex)));
    }

    static TypedCertificateEndpoint binderPattern(
            StructuralKey descriptorKey,
            TypedSlotContext boundContext,
            TypedPermutation permutation) {
        Objects.requireNonNull(descriptorKey, "descriptorKey");
        Objects.requireNonNull(boundContext, "boundContext");
        Objects.requireNonNull(permutation, "permutation");
        BinderPatternPayload pattern = new BinderPatternPayload(
                descriptorKey, boundContext, permutation);
        return new TypedCertificateEndpoint(
                Kind.BINDER_PATTERN,
                boundContext,
                CertificateSort.binderLaw(descriptorKey),
                pattern,
                StructuralKey.of(
                        "certificate-pattern/binder-automorphism",
                        Collections.emptyList(),
                        Arrays.asList(
                                descriptorKey,
                                TheoryKeys.embedding(permutation))));
    }

    public TypedCertificateEndpoint act(TypedEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (!context.equals(embedding.source())) {
            throw new IllegalArgumentException(
                    "Certificate endpoint action source must equal its context");
        }
        if (kind == Kind.ECLASS_WITNESS) {
            return invocation(new TypedInvocation(
                    (TypedEClassInterface) payload, embedding));
        }
        if (kind == Kind.INVOCATION) {
            return invocation(((TypedInvocation) payload).act(embedding));
        }
        if (kind == Kind.NODE) {
            return node(((TypedENode) payload).act(embedding));
        }
        if (kind == Kind.PORT) {
            return port(((PortValue) payload).act(embedding));
        }
        if (kind == Kind.ONE_TERM) {
            return oneTerm(((OnePort) payload).act(embedding));
        }
        if (kind == Kind.BINDER_PATTERN) {
            if (!embedding.isPermutation()) {
                throw new IllegalArgumentException(
                        "A binder-law endpoint may be acted on only by a typed permutation");
            }
            BinderPatternPayload pattern = (BinderPatternPayload) payload;
            TypedPermutation action = embedding.asRenaming().asPermutation();
            return binderPattern(
                    pattern.descriptorKey,
                    pattern.boundContext,
                    pattern.permutation.andThen(action));
        }
        return new TypedCertificateEndpoint(
                Kind.RENAMED,
                embedding.codomain(),
                sort,
                null,
                StructuralKey.branch(
                        "certificate-term/renamed",
                        Arrays.asList(expressionKey, TheoryKeys.embedding(embedding))));
    }

    public TypedSlotContext context() {
        return context;
    }

    public CertificateSort sort() {
        return sort;
    }

    public StructuralKey expressionKey() {
        return expressionKey;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    TypedENode nodePayload() {
        return kind == Kind.NODE ? (TypedENode) payload : null;
    }

    PortValue portPayload() {
        return kind == Kind.PORT ? (PortValue) payload : null;
    }

    TypedInvocation invocationPayload() {
        return kind == Kind.INVOCATION ? (TypedInvocation) payload : null;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypedCertificateEndpoint)) {
            return false;
        }
        TypedCertificateEndpoint endpoint = (TypedCertificateEndpoint) other;
        return kind == endpoint.kind
                && context.equals(endpoint.context)
                && sort.equals(endpoint.sort)
                && expressionKey.equals(endpoint.expressionKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, context, sort, expressionKey);
    }

    @Override
    public String toString() {
        return expressionKey.toString();
    }

    private static final class BinderPatternPayload {
        private final StructuralKey descriptorKey;
        private final TypedSlotContext boundContext;
        private final TypedPermutation permutation;

        private BinderPatternPayload(
                StructuralKey descriptorKey,
                TypedSlotContext boundContext,
                TypedPermutation permutation) {
            this.descriptorKey = descriptorKey;
            this.boundContext = boundContext;
            this.permutation = permutation;
        }
    }
}
