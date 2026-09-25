package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Visible source application retained until the flat-construction boundary. */
public final class FlatApplication implements FlatInput {
    private final InstantiatedOperator operator;
    private final TypedSlotContext context;
    private final List<FlatInput> operands;

    public FlatApplication(
            InstantiatedOperator operator,
            TypedSlotContext context,
            List<? extends FlatInput> operands) {
        this.operator = Objects.requireNonNull(operator, "operator");
        this.context = Objects.requireNonNull(context, "context");
        if (!operator.usesFlatConstruction()) {
            throw new IllegalArgumentException(
                    "FlatApplication requires an operator declared for flat construction");
        }
        Objects.requireNonNull(operands, "operands");
        List<FlatInput> copied = new ArrayList<>(operands.size());
        for (FlatInput operand : operands) {
            FlatInput input = Objects.requireNonNull(operand, "flat operand");
            if (!context.equals(input.context())) {
                throw new IllegalArgumentException(
                        "Every visible flat operand must use the application caller context");
            }
            if (!operator.outputType().equals(input.outputType())) {
                throw new IllegalArgumentException(
                        "Flat operand output must equal the recursive operator output type");
            }
            copied.add(input);
        }
        PortSchema schema = operator.portSchemas().get(0);
        ArityPolicy arities = ContainerLawDeclaration.arityPolicy(schema);
        if (!arities.admits(copied.size())) {
            throw new IllegalArgumentException(
                    "Visible flat source arity " + copied.size()
                            + " is not admitted by " + arities);
        }
        this.operands = Collections.unmodifiableList(copied);
    }

    public InstantiatedOperator operator() {
        return operator;
    }

    @Override
    public TypedSlotContext context() {
        return context;
    }

    public List<FlatInput> operands() {
        return operands;
    }

    @Override
    public GraphType outputType() {
        return operator.outputType();
    }

    @Override
    public StructuralKey structuralKey() {
        List<StructuralKey> children = new ArrayList<>(operands.size() + 2);
        children.add(operator.structuralKey());
        children.add(TheoryKeys.context(context));
        for (FlatInput operand : operands) {
            children.add(operand.structuralKey());
        }
        return StructuralKey.branch("flat-input/application", children);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FlatApplication)) {
            return false;
        }
        FlatApplication application = (FlatApplication) other;
        return operator.equals(application.operator)
                && context.equals(application.context)
                && operands.equals(application.operands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operator, context, operands);
    }

    @Override
    public String toString() {
        return operator.operator() + operands;
    }
}
