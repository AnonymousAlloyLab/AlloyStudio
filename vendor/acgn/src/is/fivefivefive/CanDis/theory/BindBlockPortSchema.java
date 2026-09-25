package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Schema {@code BindBlock(beta,kappa)}. */
public final class BindBlockPortSchema implements PortSchema {
    private final BinderBlockDescriptor descriptor;
    private final PortSchema bodySchema;

    public BindBlockPortSchema(
            BinderBlockDescriptor descriptor,
            PortSchema bodySchema) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.bodySchema = Objects.requireNonNull(bodySchema, "bodySchema");
    }

    public BinderBlockDescriptor descriptor() {
        return descriptor;
    }

    public PortSchema bodySchema() {
        return bodySchema;
    }

    @Override
    public Kind kind() {
        return Kind.BIND_BLOCK;
    }

    @Override
    public Set<String> typeVariables() {
        Set<String> variables = new TreeSet<>(descriptor.typeVariables());
        variables.addAll(bodySchema.typeVariables());
        return Collections.unmodifiableSet(variables);
    }

    @Override
    public BindBlockPortSchema substitute(Map<String, GraphType> substitution) {
        return new BindBlockPortSchema(
                descriptor.substitute(substitution),
                bodySchema.substitute(substitution));
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.branch(
                "schema/bind-block",
                Arrays.asList(descriptor.structuralKey(), bodySchema.structuralKey()));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BindBlockPortSchema)) {
            return false;
        }
        BindBlockPortSchema schema = (BindBlockPortSchema) other;
        return descriptor.equals(schema.descriptor) && bodySchema.equals(schema.bodySchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind(), descriptor, bodySchema);
    }

    @Override
    public String toString() {
        return "BindBlock(" + descriptor + "," + bodySchema + ")";
    }
}
