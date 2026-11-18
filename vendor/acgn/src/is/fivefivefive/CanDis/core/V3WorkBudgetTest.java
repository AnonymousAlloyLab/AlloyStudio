package is.fivefivefive.CanDis.core;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import is.fivefivefive.CanDis.WorkBudget;
import is.fivefivefive.CanDis.metric.TraceResources;

/** Request budget controls at the v3 orbit, SET equality and JOIN boundaries. */
public final class V3WorkBudgetTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void refuse(long fuel, long allocations, Runnable action) {
        WorkBudget.begin(fuel, allocations);
        try {
            action.run();
            throw new AssertionError("Missing request-budget refusal");
        } catch (WorkBudget.Exhausted exhausted) {
            check(WorkBudget.exhausted(), "Refusal must be sticky");
            check(WorkBudget.used() <= fuel, "Charged beyond request fuel");
            check(WorkBudget.allocated() <= allocations, "Allocated beyond request allowance");
        } finally {
            WorkBudget.end();
        }
    }

    private static void joinFuel() {
        int[] reads = {0};
        Runnable join = () -> {
            var result = JoinSegmentation.of("atom", node -> {
                reads[0]++;
                return List.of();
            }, node -> 1, 1);
            check(result.operands().equals(List.of("atom")), "Complete segmentation changed");
        };
        refuse(0, 100, join);
        check(reads[0] == 0, "Refusal preceded the first operand read");
        refuse(1, 100, join);
        check(reads[0] == 1, "Refusal preceded output emission");
        WorkBudget.begin(2, 100);
        try {
            join.run();
            check(WorkBudget.used() == 2, "One input and one output visit were charged");
        } finally {
            WorkBudget.end();
        }
    }

    private static List<?> enumerate(int size) {
        try {
            var method = FastBinderOrbit.class.getDeclaredMethod("enumerate", List.class, int[].class);
            method.setAccessible(true);
            int[] identity = IntStream.range(0, size).toArray();
            return (List<?>) method.invoke(null, List.of(IntStream.range(0, size).boxed().toList()), identity);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(error);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    public static void main(String[] args) {
        joinFuel();
        refuse(0, 100, () -> FastSetDedup.sameModuloOrbit(null, null, Map.of(), new int[]{1}));
        refuse(0, 100, () -> enumerate(2));
        refuse(100, 0, () -> enumerate(2));
        check(enumerate(3).size() == 5, "Complete orbit changed");
        try {
            enumerate(7);
            throw new AssertionError("Oversized factorial orbit accepted");
        } catch (TraceResources.Exhausted exhausted) {
            check(exhausted.getMessage().startsWith("FAST_ORBIT_BUDGET"), "Wrong native resource refusal");
        }
        System.out.println("V3WorkBudgetTest: checks passed");
    }
}
