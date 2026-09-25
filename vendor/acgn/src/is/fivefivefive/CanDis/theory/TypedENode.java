package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Well-typed flexible-arity e-node with a private, invariant-preserving constructor. */
public final class TypedENode implements HasSlotSupport {
    private final InstantiatedOperator operator;
    private final TypedSlotContext context;
    private final List<PortValue> ports;
    private final TypedSlotContext support;
    private final StructuralKey structuralKey;

    private TypedENode(
            InstantiatedOperator operator,
            TypedSlotContext context,
            List<? extends PortValue> ports) {
        this.operator = Objects.requireNonNull(operator, "operator");
        this.context = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(ports, "ports");
        if (ports.size() != operator.portSchemas().size()) {
            throw new IllegalArgumentException("Node port count does not match its signature");
        }
        List<PortValue> copied = new ArrayList<>(ports.size());
        TypedSlotContext computedSupport = TypedSlotContext.empty();
        for (int index = 0; index < ports.size(); index++) {
            PortValue port = Objects.requireNonNull(ports.get(index), "port");
            if (!operator.portSchemas().get(index).equals(port.schema())) {
                throw new IllegalArgumentException("Node port schema mismatch at index " + index);
            }
            if (!context.equals(port.context())) {
                throw new IllegalArgumentException("Every node port must use the node caller context");
            }
            rejectUnlicensedFlatEmpty(PortPath.at(index), port);
            computedSupport = computedSupport.union(port.support());
            copied.add(port);
        }
        this.ports = Collections.unmodifiableList(copied);
        this.support = computedSupport;
        this.structuralKey = buildStructuralKey();
    }

    /** Constructs an operator that has no recursive associative flat port. */
    public static TypedENode construct(
            InstantiatedOperator operator,
            TypedSlotContext context,
            List<? extends PortValue> ports) {
        Objects.requireNonNull(operator, "operator");
        if (operator.usesFlatConstruction()) {
            throw new IllegalArgumentException(
                    "Associative operators must be constructed through flatConstruct");
        }
        return new TypedENode(operator, context, ports);
    }

    /**
     * Flattens only visible same-headed source applications. Opaque invocation
     * leaves are copied without inspecting their e-classes.
     */
    public static TypedENode flatConstruct(
            FlatApplication source,
            NodeSealer sealer) {
        requireStructuralFlatAuthority(Objects.requireNonNull(source, "source"));
        return flattenVisible(source, sealer);
    }

    private static TypedENode flattenVisible(
            FlatApplication source,
            NodeSealer sealer) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sealer, "sealer");
        InstantiatedOperator operator = source.operator();
        if (!operator.usesFlatConstruction()) {
            throw new IllegalArgumentException("Operator is not declared for flat construction");
        }
        PortSchema containerSchema = operator.portSchemas().get(0);
        OnePortSchema elementSchema = (OnePortSchema) OperatorDeclaration.elementSchema(containerSchema);
        List<PortValue> elements = new ArrayList<>();
        collectVisibleElements(source, operator, elementSchema, sealer, elements);
        PortValue container = makeContainer(containerSchema, source.context(), elements);
        return new TypedENode(operator, source.context(), Collections.singletonList(container));
    }

    public static CertifiedFlatConstruction flatConstructCertified(
            FlatApplication source,
            NodeSealer sealer,
            SemanticProfile semanticProfile) {
        TypedENode node = flattenVisible(source, sealer);
        PortValue container = node.ports().get(
                source.operator().flatLicense().path().portIndex());
        if (container instanceof SetPort
                && ((SetPort) container).elements().size() == 1) {
            PortValue sole = ((SetPort) container).elements().get(0);
            if (!(sole instanceof OnePort)) {
                throw new IllegalStateException(
                        "A flat singleton must inhabit the declared One element schema");
            }
            OnePort singleton = (OnePort) sole;
            return new CertifiedFlatConstruction(
                    singleton,
                    FlatConstructionCertificate.createSingletonProduction(
                            source, singleton, semanticProfile));
        }
        return new CertifiedFlatConstruction(
                node,
                FlatConstructionCertificate.createProduction(
                        source, node, semanticProfile));
    }

    /** Constructs one nonflat container and retains its exact source occurrence order. */
    public static CertifiedContainerConstruction constructContainerCertified(
            InstantiatedOperator operator,
            PortPath path,
            TypedSlotContext context,
            List<? extends PortValue> inputOccurrences,
            SemanticProfile semanticProfile) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(inputOccurrences, "inputOccurrences");
        if (operator.usesFlatConstruction()
                || path.depth() != 0
                || operator.portSchemas().size() != 1
                || path.portIndex() != 0) {
            throw new IllegalArgumentException(
                    "Certified nonflat container construction requires one root container port");
        }
        PortSchema schema = operator.schemaAt(path);
        PortValue container = makeContainer(
                schema, context, new ArrayList<>(inputOccurrences));
        TypedENode node = construct(
                operator, context, Collections.singletonList(container));
        return new CertifiedContainerConstruction(
                node,
                ContainerConstructionCertificate.createProduction(
                        operator,
                        path,
                        context,
                        inputOccurrences,
                        node,
                        semanticProfile));
    }

    public static CertifiedDependentChainConstruction constructDependentChainCertified(
            DependentChainApplication source,
            SemanticProfile semanticProfile) {
        return constructDependentChainCertified(
                source,
                semanticProfile,
                StructuralKey.branch(
                        "dependent-chain-semantic-source-v1",
                        List.of(source.structuralKey())));
    }

    public static CertifiedDependentChainConstruction constructDependentChainCertified(
            DependentChainApplication source,
            SemanticProfile semanticProfile,
            StructuralKey sourceOccurrenceCommitment) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        Objects.requireNonNull(
                sourceOccurrenceCommitment, "sourceOccurrenceCommitment");
        List<PortSchema> elementSchemas = new ArrayList<>();
        List<PortValue> elements = new ArrayList<>();
        for (OnePort leaf : source.leaves()) {
            elementSchemas.add(leaf.schema());
            elements.add(leaf);
        }
        SeqPortSchema chainSchema = SeqPortSchema.dependent(elementSchemas);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                source.kind().operatorIdentity(),
                List.of(chainSchema),
                source.outputType(),
                Collections.singletonMap(
                        PortPath.at(0), ContainerLawDeclaration.of(
                                ContainerLawDeclaration.Kind.SEQ,
                                false,
                                false,
                                false,
                                false)),
                null).instantiateMonomorphic();
        TypedENode node = construct(
                operator,
                source.context(),
                List.of(new SeqPort(chainSchema, source.context(), elements)));
        return new CertifiedDependentChainConstruction(
                node,
                DependentChainCertificate.createProduction(
                        source,
                        node,
                        semanticProfile,
                        sourceOccurrenceCommitment));
    }

    private static void collectVisibleElements(
            FlatApplication source,
            InstantiatedOperator rootOperator,
            OnePortSchema elementSchema,
            NodeSealer sealer,
            List<PortValue> output) {
        for (FlatInput input : source.operands()) {
            if (input instanceof FlatApplication) {
                FlatApplication application = (FlatApplication) input;
                if (rootOperator.equals(application.operator())) {
                    collectVisibleElements(application, rootOperator, elementSchema, sealer, output);
                    continue;
                }
                TypedENode nested = flattenVisible(application, sealer);
                TypedInvocation invocation = Objects.requireNonNull(
                        sealer.seal(nested), "sealed invocation");
                validateSealedInvocation(nested, invocation);
                output.add(new OnePort(
                        elementSchema,
                        source.context(),
                        new InvocationPortLeaf(invocation)));
                continue;
            }
            OnePort port = ((FlatLeaf) input).port();
            if (!elementSchema.equals(port.schema()) || !source.context().equals(port.context())) {
                throw new IllegalArgumentException("Flat leaf does not match the operator element port");
            }
            output.add(port);
        }
    }

    private static void validateSealedInvocation(
            TypedENode node,
            TypedInvocation invocation) {
        if (!node.outputType().equals(invocation.outputType())) {
            throw new IllegalArgumentException("Node sealer changed the nested node output type");
        }
        if (!node.context().equals(invocation.callerContext())) {
            throw new IllegalArgumentException("Node sealer changed the nested caller context");
        }
        if (!node.support().equals(invocation.support())) {
            throw new IllegalArgumentException("Node sealer changed the nested node support");
        }
    }

    private static PortValue makeContainer(
            PortSchema schema,
            TypedSlotContext context,
            List<PortValue> elements) {
        if (schema instanceof SeqPortSchema) {
            return new SeqPort((SeqPortSchema) schema, context, elements);
        }
        if (schema instanceof BagPortSchema) {
            return new BagPort((BagPortSchema) schema, context, elements);
        }
        if (schema instanceof SetPortSchema) {
            return new SetPort((SetPortSchema) schema, context, elements);
        }
        throw new IllegalStateException("Flat operator port is not a container");
    }

    private static void requireStructuralFlatAuthority(FlatApplication source) {
        InstantiatedOperator operator = source.operator();
        ContainerLawDeclaration declaration = operator.lawForPath(
                operator.flatLicense().path());
        for (ContainerLawCertificate certificate : declaration.certificates().values()) {
            if (certificate.authority()
                    == ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY) {
                throw new IllegalArgumentException(
                        "Production flat construction must retain its concrete certificate");
            }
        }
        for (FlatInput input : source.operands()) {
            if (input instanceof FlatApplication) {
                requireStructuralFlatAuthority((FlatApplication) input);
            }
        }
    }

    private void rejectUnlicensedFlatEmpty(PortPath path, PortValue port) {
        boolean empty = (port instanceof SeqPort && ((SeqPort) port).isEmpty())
                || (port instanceof BagPort && ((BagPort) port).isEmpty())
                || (port instanceof SetPort && ((SetPort) port).isEmpty());
        if (empty && operator.flatLicense().enabled()
                && operator.flatLicense().path().equals(path)) {
            if (!operator.lawForPath(path).hasUnit()) {
                throw new IllegalArgumentException(
                        "Empty flat port at " + path
                                + " requires an explicit unit license");
            }
        }
        PortPath childPath = path.child();
        if (port instanceof SeqPort) {
            for (PortValue element : ((SeqPort) port).elements()) {
                rejectUnlicensedFlatEmpty(childPath, element);
            }
        } else if (port instanceof BagPort) {
            for (PortValue element : ((BagPort) port).occurrences()) {
                rejectUnlicensedFlatEmpty(childPath, element);
            }
        } else if (port instanceof SetPort) {
            for (PortValue element : ((SetPort) port).elements()) {
                rejectUnlicensedFlatEmpty(childPath, element);
            }
        } else if (port instanceof BindPort) {
            rejectUnlicensedFlatEmpty(childPath, ((BindPort) port).body());
        } else if (port instanceof BindBlockPort) {
            rejectUnlicensedFlatEmpty(childPath, ((BindBlockPort) port).body());
        }
    }

    public InstantiatedOperator operator() {
        return operator;
    }

    public TypedSlotContext context() {
        return context;
    }

    public List<PortValue> ports() {
        return ports;
    }

    public GraphType outputType() {
        return operator.outputType();
    }

    @Override
    public TypedSlotContext support() {
        return support;
    }

    /**
     * Narrows only the ambient caller context to this node's exact free-slot
     * support. The port syntax and every invocation target are preserved.
     */
    public TypedENode inExactSupportContext() {
        return ExactContextRestrictor.restrictToSupport(this);
    }

    public TypedENode act(TypedEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (!context.equals(embedding.source())) {
            throw new IllegalArgumentException("Node action source must equal its caller context");
        }
        List<PortValue> acted = new ArrayList<>(ports.size());
        for (PortValue port : ports) {
            acted.add(port.act(embedding));
        }
        return new TypedENode(operator, embedding.codomain(), acted);
    }

    /**
     * Rebuilds this already-flat node after graph-relative leaf and port
     * normalization. The input is port syntax, so this operation cannot expose
     * or flatten an opaque invocation.
     */
    TypedENode rebuildCanonicalCandidate(
            TypedSlotContext targetContext,
            List<? extends PortValue> normalizedPorts) {
        return new TypedENode(operator, targetContext, normalizedPorts);
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    private StructuralKey buildStructuralKey() {
        List<StructuralKey> children = new ArrayList<>(ports.size() + 2);
        children.add(operator.structuralKey());
        children.add(TheoryKeys.context(context));
        for (PortValue port : ports) {
            children.add(port.structuralKey());
        }
        return StructuralKey.branch("e-node", children);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypedENode)) {
            return false;
        }
        TypedENode node = (TypedENode) other;
        return operator.equals(node.operator)
                && context.equals(node.context)
                && ports.equals(node.ports);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operator, context, ports);
    }

    @Override
    public String toString() {
        return operator.operator() + ports;
    }
}
