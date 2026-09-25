package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Schema {@code Bind(tau,kappa)}. */
public final class BindPortSchema implements PortSchema {
    private final GraphType boundType;
    private final PortSchema bodySchema;

    public BindPortSchema(GraphType boundType, PortSchema bodySchema) {
        this.boundType = Objects.requireNonNull(boundType, "boundType");
        this.bodySchema = Objects.requireNonNull(bodySchema, "bodySchema");
    }

    public GraphType boundType() {
        return boundType;
    }

    public PortSchema bodySchema() {
        return bodySchema;
    }

    @Override
    public Kind kind() {
        return Kind.BIND;
    }

    @Override
    public Set<String> typeVariables() {
        Set<String> variables = new TreeSet<>(boundType.typeVariables());
        variables.addAll(bodySchema.typeVariables());
        return Collections.unmodifiableSet(variables);
    }

    @Override
    public BindPortSchema substitute(Map<String, GraphType> substitution) {
        return new BindPortSchema(
                boundType.substitute(substitution), bodySchema.substitute(substitution));
    }

    @Override
    public StructuralKey structuralKey() {
        ArrayList<StructuralKey> children = new ArrayList<>(2);
        children.add(TheoryKeys.type(boundType));
        children.add(bodySchema.structuralKey());
        return StructuralKey.branch("schema/bind", children);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BindPortSchema)) {
            return false;
        }
        BindPortSchema schema = (BindPortSchema) other;
        return boundType.equals(schema.boundType) && bodySchema.equals(schema.bodySchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind(), boundType, bodySchema);
    }

    @Override
    public String toString() {
        return "Bind(" + boundType + "," + bodySchema + ")";
    }
}
