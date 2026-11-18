package is.fivefivefive.CanDis.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import is.fivefivefive.CanDis.WorkBudget;

/** Actual-subtree frontier; semantics/width contract: JoinSegmentation.lean. */
public final class JoinSegmentation {
    private JoinSegmentation() { }

    public record Result<T>(List<T> operands, int inputVisits, int outputVisits) {
        public Result { operands = List.copyOf(operands); }
    }

    private record Frame<T>(T node, boolean finish) { }
    private record Emission<T>(Plan<T> plan, boolean expose) { }
    private record Plan<T>(T node, Plan<T> left, Plan<T> right, T first, T last,
            boolean exposeLeft, boolean exposeRight) { }

    /** Children must be empty for an opaque operand, or the two actual JOIN children. */
    public static <T> Result<T> of(T root, Function<T, List<T>> children,
            ToIntFunction<T> arity, int maxVisits) {
        var pending = new ArrayDeque<Frame<T>>();
        var completed = new ArrayDeque<Plan<T>>();
        pending.push(new Frame<>(root, false));
        int inputVisits = 0;
        while (!pending.isEmpty()) {
            WorkBudget.charge(1);
            var frame = pending.pop();
            T node = frame.node();
            if (frame.finish()) {
                var right = completed.pop();
                var left = completed.pop();
                boolean exposeLeft = arity.applyAsInt(left.last()) >= 2;
                boolean exposeRight = arity.applyAsInt(right.first()) >= 2;
                completed.push(new Plan<>(node, left, right,
                        exposeLeft ? left.first() : left.node(),
                        exposeRight ? right.last() : right.node(), exposeLeft, exposeRight));
                continue;
            }
            if (++inputVisits > maxVisits)
                throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("JOIN_SEGMENTATION_BUDGET");
            if (arity.applyAsInt(node) <= 0)
                throw new IllegalArgumentException("JOIN operand lost its positive arity");
            List<T> parts = children.apply(node);
            if (parts.isEmpty()) {
                completed.push(new Plan<>(node, null, null, node, node, false, false));
            } else {
                if (parts.size() != 2) throw new IllegalArgumentException("Nonbinary JOIN source");
                pending.push(new Frame<>(node, true));
                pending.push(new Frame<>(parts.get(1), false));
                pending.push(new Frame<>(parts.get(0), false));
            }
        }
        // A rope-shaped cut avoids copying child frontiers at every ancestor.
        var emit = new ArrayDeque<Emission<T>>();
        emit.push(new Emission<>(completed.pop(), true));
        var operands = new ArrayList<T>();
        int outputVisits = 0;
        while (!emit.isEmpty()) {
            WorkBudget.charge(1);
            var next = emit.pop();
            var plan = next.plan();
            outputVisits++;
            if (!next.expose() || plan.left() == null) {
                operands.add(plan.node());
            } else {
                emit.push(new Emission<>(plan.right(), plan.exposeRight()));
                emit.push(new Emission<>(plan.left(), plan.exposeLeft()));
            }
        }
        return new Result<>(operands, inputVisits, outputVisits);
    }
}
