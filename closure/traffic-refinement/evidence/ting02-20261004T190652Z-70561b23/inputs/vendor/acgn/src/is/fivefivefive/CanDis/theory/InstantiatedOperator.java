package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A fully instantiated typed operator signature. */
public final class InstantiatedOperator {
    private final OperatorDeclaration declaration;
    private final Map<String, GraphType> typeArguments;
    private final List<PortSchema> portSchemas;
    private final GraphType outputType;

    InstantiatedOperator(
            OperatorDeclaration declaration,
            Map<String, GraphType> typeArguments) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.typeArguments = Collections.unmodifiableMap(new LinkedHashMap<>(typeArguments));
        List<PortSchema> schemas = new ArrayList<>(declaration.portSchemas().size());
        for (PortSchema schema : declaration.portSchemas()) {
            schemas.add(schema.substitute(this.typeArguments));
        }
        this.portSchemas = Collections.unmodifiableList(schemas);
        this.outputType = declaration.outputType().substitute(this.typeArguments);
    }

    public OperatorDeclaration declaration() {
        return declaration;
    }

    public String operator() {
        return declaration.operator();
    }

    public Map<String, GraphType> typeArguments() {
        return typeArguments;
    }

    public List<PortSchema> portSchemas() {
        return portSchemas;
    }

    public GraphType outputType() {
        return outputType;
    }

    public Map<PortPath, ContainerLawDeclaration> containerLaws() {
        return declaration.containerLaws();
    }

    public ContainerLawDeclaration lawForPort(int index) {
        return lawForPath(PortPath.at(index));
    }

    public ContainerLawDeclaration lawForPath(PortPath path) {
        Objects.requireNonNull(path, "path");
        ContainerLawDeclaration law = declaration.containerLaws().get(path);
        if (law == null) {
            throw new IllegalArgumentException("Port path " + path + " has no container laws");
        }
        return law;
    }

    public PortSchema schemaAt(PortPath path) {
        Objects.requireNonNull(path, "path");
        int index = path.portIndex();
        if (index < 0 || index >= portSchemas.size()) {
            throw new IllegalArgumentException("Port path is outside this operator: " + path);
        }
        PortSchema schema = portSchemas.get(index);
        for (int depth = 0; depth < path.depth(); depth++) {
            if (schema instanceof SeqPortSchema) {
                SeqPortSchema sequence = (SeqPortSchema) schema;
                if (sequence.isDependent()) {
                    throw new IllegalArgumentException(
                            "PortPath cannot descend through a positional dependent sequence");
                }
                schema = sequence.elementSchema();
            } else if (schema instanceof BagPortSchema) {
                schema = ((BagPortSchema) schema).elementSchema();
            } else if (schema instanceof SetPortSchema) {
                schema = ((SetPortSchema) schema).elementSchema();
            } else if (schema instanceof BindPortSchema) {
                schema = ((BindPortSchema) schema).bodySchema();
            } else if (schema instanceof BindBlockPortSchema) {
                schema = ((BindBlockPortSchema) schema).bodySchema();
            } else {
                throw new IllegalArgumentException(
                        "Port path descends through a nonrecursive schema: " + path);
            }
        }
        return schema;
    }

    public boolean usesFlatConstruction() {
        return declaration.usesFlatConstruction();
    }

    public FlatLicense flatLicense() {
        return declaration.flatLicense();
    }

    public StructuralKey structuralKey() {
        List<String> scalars = new ArrayList<>();
        scalars.add(operator());
        for (String parameter : declaration.typeParameters()) {
            scalars.add(parameter);
        }
        List<StructuralKey> children = new ArrayList<>();
        children.add(declaration.structuralKey());
        for (String parameter : declaration.typeParameters()) {
            children.add(StructuralKey.of(
                    "type-argument",
                    Collections.singletonList(parameter),
                    Collections.singletonList(TheoryKeys.type(typeArguments.get(parameter)))));
        }
        for (PortSchema schema : portSchemas) {
            children.add(schema.structuralKey());
        }
        children.add(TheoryKeys.type(outputType));
        return StructuralKey.of("instantiated-operator", scalars, children);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof InstantiatedOperator)) {
            return false;
        }
        InstantiatedOperator operator = (InstantiatedOperator) other;
        return declaration.equals(operator.declaration)
                && typeArguments.equals(operator.typeArguments)
                && portSchemas.equals(operator.portSchemas)
                && outputType.equals(operator.outputType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(declaration, typeArguments, portSchemas, outputType);
    }

    @Override
    public String toString() {
        return declaration.operator() + typeArguments + portSchemas + " => " + outputType;
    }
}
