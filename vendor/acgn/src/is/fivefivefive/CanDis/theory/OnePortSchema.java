package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Schema {@code One(tau)}. */
public final class OnePortSchema implements PortSchema {
    private final GraphType type;

    public OnePortSchema(GraphType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public GraphType type() {
        return type;
    }

    @Override
    public Kind kind() {
        return Kind.ONE;
    }

    @Override
    public Set<String> typeVariables() {
        return type.typeVariables();
    }

    @Override
    public OnePortSchema substitute(Map<String, GraphType> substitution) {
        return new OnePortSchema(type.substitute(substitution));
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.branch("schema/one", Collections.singletonList(TheoryKeys.type(type)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OnePortSchema && type.equals(((OnePortSchema) other).type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind(), type);
    }

    @Override
    public String toString() {
        return "One(" + type + ")";
    }
}
