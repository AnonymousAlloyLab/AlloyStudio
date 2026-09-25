package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Objects;

/** Already-materialized slot or opaque invocation operand for flat construction. */
public final class FlatLeaf implements FlatInput {
    private final OnePort port;

    public FlatLeaf(OnePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    public OnePort port() {
        return port;
    }

    @Override
    public GraphType outputType() {
        return port.schema().type();
    }

    @Override
    public TypedSlotContext context() {
        return port.context();
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.branch(
                "flat-input/leaf", Collections.singletonList(port.structuralKey()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FlatLeaf && port.equals(((FlatLeaf) other).port);
    }

    @Override
    public int hashCode() {
        return port.hashCode();
    }

    @Override
    public String toString() {
        return port.toString();
    }
}
