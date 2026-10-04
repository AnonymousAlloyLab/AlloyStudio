package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Polymorphic typed operator declaration {@code forall a.(ports) => output}. */
public final class OperatorDeclaration {
    private final String operator;
    private final List<String> typeParameters;
    private final List<PortSchema> portSchemas;
    private final GraphType outputType;
    private final Map<PortPath, ContainerLawDeclaration> containerLaws;
    private final FlatLicense flatLicense;

    public OperatorDeclaration(
            String operator,
            List<String> typeParameters,
            List<PortSchema> portSchemas,
            GraphType outputType,
            Map<PortPath, ContainerLawDeclaration> containerLaws,
            Integer flatPortIndex) {
        this.operator = requireName(operator, "operator");
        this.typeParameters = copyTypeParameters(typeParameters);
        this.portSchemas = copySchemas(portSchemas);
        this.outputType = Objects.requireNonNull(outputType, "outputType");
        this.containerLaws = copyLaws(containerLaws);
        this.flatLicense = flatPortIndex == null
                ? FlatLicense.none() : FlatLicense.atRootPort(flatPortIndex);
        validateTypeVariables();
        validateContainerLaws();
        validateFlatPort();
    }

    public static OperatorDeclaration monomorphic(
            String operator,
            List<PortSchema> portSchemas,
            GraphType outputType,
            Map<PortPath, ContainerLawDeclaration> containerLaws,
            Integer flatPortIndex) {
        return new OperatorDeclaration(
                operator,
                Collections.emptyList(),
                portSchemas,
                outputType,
                containerLaws,
                flatPortIndex);
    }

    private static String requireName(String value, String name) {
        if (!AlloyTypeBridge.isAdmittedIdentity(value)) {
            throw new IllegalArgumentException(
                    name + " must be a well-formed visible identity");
        }
        return value;
    }

    private static List<String> copyTypeParameters(List<String> parameters) {
        Objects.requireNonNull(parameters, "typeParameters");
        List<String> result = new ArrayList<>(parameters.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String parameter : parameters) {
            String name = requireName(parameter, "type parameter");
            if (!seen.add(name)) {
                throw new IllegalArgumentException("Duplicate type parameter: " + name);
            }
            result.add(name);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<PortSchema> copySchemas(List<PortSchema> schemas) {
        Objects.requireNonNull(schemas, "portSchemas");
        List<PortSchema> result = new ArrayList<>(schemas.size());
        for (PortSchema schema : schemas) {
            result.add(Objects.requireNonNull(schema, "port schema"));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<PortPath, ContainerLawDeclaration> copyLaws(
            Map<PortPath, ContainerLawDeclaration> laws) {
        Objects.requireNonNull(laws, "containerLaws");
        Map<PortPath, ContainerLawDeclaration> result = new TreeMap<>();
        for (Map.Entry<PortPath, ContainerLawDeclaration> entry : laws.entrySet()) {
            result.put(
                    Objects.requireNonNull(entry.getKey(), "law port path"),
                    Objects.requireNonNull(entry.getValue(), "container law"));
        }
        return Collections.unmodifiableMap(result);
    }

    private void validateTypeVariables() {
        Set<String> declared = new LinkedHashSet<>(typeParameters);
        Set<String> used = new LinkedHashSet<>(outputType.typeVariables());
        for (PortSchema schema : portSchemas) {
            used.addAll(schema.typeVariables());
        }
        if (!declared.containsAll(used)) {
            used.removeAll(declared);
            throw new IllegalArgumentException("Undeclared type variables: " + used);
        }
    }

    private void validateContainerLaws() {
        Map<PortPath, PortSchema> required = new TreeMap<>();
        for (int index = 0; index < portSchemas.size(); index++) {
            collectContainerSchemas(portSchemas.get(index), PortPath.at(index), required);
        }
        if (!containerLaws.keySet().equals(required.keySet())) {
            throw new IllegalArgumentException(
                    "Every Seq/Bag/Set schema path requires exactly one explicit law declaration");
        }
        for (Map.Entry<PortPath, ContainerLawDeclaration> entry : containerLaws.entrySet()) {
            entry.getValue().validateEvidenceFor(
                    operator,
                    outputType,
                    entry.getKey(),
                    required.get(entry.getKey()),
                    false);
        }
    }

    PortSchema schemaAt(PortPath path) {
        Objects.requireNonNull(path, "path");
        if (path.portIndex() >= portSchemas.size()) {
            throw new IllegalArgumentException("Port path is outside this operator");
        }
        PortSchema schema = portSchemas.get(path.portIndex());
        for (int depth = 0; depth < path.depth(); depth++) {
            schema = childSchema(schema);
            if (schema == null) {
                throw new IllegalArgumentException("Port path is outside this schema");
            }
        }
        return schema;
    }

    private static void collectContainerSchemas(
            PortSchema schema,
            PortPath path,
            Map<PortPath, PortSchema> output) {
        if (schema.kind() == PortSchema.Kind.SEQ
                || schema.kind() == PortSchema.Kind.BAG
                || schema.kind() == PortSchema.Kind.SET) {
            output.put(path, schema);
        }
        PortSchema child = childSchema(schema);
        if (child != null) {
            collectContainerSchemas(child, path.child(), output);
        }
    }

    private static PortSchema childSchema(PortSchema schema) {
        if (schema instanceof SeqPortSchema) {
            SeqPortSchema sequence = (SeqPortSchema) schema;
            return sequence.isDependent() ? null : sequence.elementSchema();
        }
        if (schema instanceof BagPortSchema) {
            return ((BagPortSchema) schema).elementSchema();
        }
        if (schema instanceof SetPortSchema) {
            return ((SetPortSchema) schema).elementSchema();
        }
        if (schema instanceof BindPortSchema) {
            return ((BindPortSchema) schema).bodySchema();
        }
        if (schema instanceof BindBlockPortSchema) {
            return ((BindBlockPortSchema) schema).bodySchema();
        }
        return null;
    }

    private void validateFlatPort() {
        if (!flatLicense.enabled()) {
            return;
        }
        int flatPortIndex = flatLicense.path().portIndex();
        if (flatPortIndex < 0 || flatPortIndex >= portSchemas.size()) {
            throw new IllegalArgumentException("Flat port index is out of range");
        }
        if (portSchemas.size() != 1 || flatPortIndex != 0) {
            throw new IllegalArgumentException(
                    "Visible associative flattening currently requires one container-valued port");
        }
        PortSchema schema = portSchemas.get(flatPortIndex);
        PortSchema element = elementSchema(schema);
        if (!(element instanceof OnePortSchema)
                || !((OnePortSchema) element).type().equals(outputType)) {
            throw new IllegalArgumentException(
                    "A flat operator container must contain One(outputType) elements");
        }
        if (!containerLaws.get(PortPath.at(flatPortIndex)).associative()) {
            throw new IllegalArgumentException("A flat port requires an associative law declaration");
        }
        ArityPolicy arities = ContainerLawDeclaration.arityPolicy(schema);
        arities.requireFlatSpliceClosure("A flat port");
        if (arities.admitsZero()
                && !containerLaws.get(PortPath.at(flatPortIndex)).hasUnit()) {
            throw new IllegalArgumentException(
                    "A flat port admitting zero requires an exact unit license");
        }
    }

    static PortSchema elementSchema(PortSchema schema) {
        if (schema instanceof SeqPortSchema) {
            return ((SeqPortSchema) schema).elementSchema();
        }
        if (schema instanceof BagPortSchema) {
            return ((BagPortSchema) schema).elementSchema();
        }
        if (schema instanceof SetPortSchema) {
            return ((SetPortSchema) schema).elementSchema();
        }
        throw new IllegalArgumentException("Schema is not an outer container: " + schema);
    }

    public String operator() {
        return operator;
    }

    public List<String> typeParameters() {
        return typeParameters;
    }

    public List<PortSchema> portSchemas() {
        return portSchemas;
    }

    public GraphType outputType() {
        return outputType;
    }

    public Map<PortPath, ContainerLawDeclaration> containerLaws() {
        return containerLaws;
    }

    public Integer flatPortIndex() {
        return flatLicense.enabled() ? flatLicense.path().portIndex() : null;
    }

    public FlatLicense flatLicense() {
        return flatLicense;
    }

    public boolean usesFlatConstruction() {
        return flatLicense.enabled();
    }

    public InstantiatedOperator instantiate(Map<String, GraphType> substitution) {
        Objects.requireNonNull(substitution, "substitution");
        if (!substitution.keySet().equals(new LinkedHashSet<>(typeParameters))) {
            throw new IllegalArgumentException(
                    "Instantiation must assign exactly the declared type parameters");
        }
        Map<String, GraphType> ordered = new LinkedHashMap<>();
        for (String parameter : typeParameters) {
            ordered.put(parameter, Objects.requireNonNull(
                    substitution.get(parameter), "instantiated type"));
        }
        return new InstantiatedOperator(this, ordered);
    }

    public InstantiatedOperator instantiateMonomorphic() {
        if (!typeParameters.isEmpty()) {
            throw new IllegalStateException("Polymorphic operator requires a type substitution");
        }
        return instantiate(Collections.emptyMap());
    }

    public StructuralKey structuralKey() {
        List<String> scalars = new ArrayList<>();
        scalars.add(operator);
        scalars.addAll(typeParameters);
        scalars.add(flatLicense.toString());
        List<StructuralKey> children = new ArrayList<>();
        for (PortSchema schema : portSchemas) {
            children.add(schema.structuralKey());
        }
        children.add(TheoryKeys.type(outputType));
        children.add(flatLicense.structuralKey());
        for (Map.Entry<PortPath, ContainerLawDeclaration> entry : containerLaws.entrySet()) {
            children.add(StructuralKey.of(
                    "port-law",
                    Collections.singletonList(entry.getKey().toString()),
                    Collections.singletonList(entry.getValue().structuralKey())));
        }
        return StructuralKey.of("operator-declaration", scalars, children);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OperatorDeclaration)) {
            return false;
        }
        OperatorDeclaration declaration = (OperatorDeclaration) other;
        return operator.equals(declaration.operator)
                && typeParameters.equals(declaration.typeParameters)
                && portSchemas.equals(declaration.portSchemas)
                && outputType.equals(declaration.outputType)
                && containerLaws.equals(declaration.containerLaws)
                && flatLicense.equals(declaration.flatLicense);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                operator, typeParameters, portSchemas, outputType, containerLaws, flatLicense);
    }

    @Override
    public String toString() {
        return operator + typeParameters + portSchemas + " => " + outputType;
    }
}
