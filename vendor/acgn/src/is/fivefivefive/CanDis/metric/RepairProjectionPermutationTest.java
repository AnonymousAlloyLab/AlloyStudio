package is.fivefivefive.CanDis.metric;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import is.fivefivefive.CanDis.metric.RepairView.ContainerKind;
import is.fivefivefive.CanDis.metric.RepairView.Node;

/** Unsealed fixtures exercise only the private duplicate-comparison kernel. */
public final class RepairProjectionPermutationTest {
    private static final Method SAME;
    private static int checks;
    static {
        try {
            SAME = RepairProjection.class.getDeclaredMethod("sameProjectedNode", Node.class, Node.class);
            SAME.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public static void main(String[] args) throws Exception {
        Node a = atom("a"), b = atom("b"), c = atom("c");
        check(same(container(ContainerKind.SET, true, a, b),
                container(ContainerKind.SET, true, b, a)), "Set permutation");
        check(same(container(ContainerKind.BAG, true, a, a, b),
                container(ContainerKind.BAG, true, b, a, a)), "Bag permutation");
        check(!same(container(ContainerKind.BAG, true, a, a, b),
                container(ContainerKind.BAG, true, a, b, b)), "Bag multiplicity");
        check(!same(container(ContainerKind.SEQUENCE, false, a, b),
                container(ContainerKind.SEQUENCE, false, b, a)), "ordered Seq barrier");
        check(!same(container(ContainerKind.SET, true, a, b),
                container(ContainerKind.SET, true, a, c)), "different atom");
        Node ab = container(ContainerKind.SET, true, a, b);
        Node ba = container(ContainerKind.SET, true, b, a);
        check(same(container(ContainerKind.SEQUENCE, false, ab),
                container(ContainerKind.SEQUENCE, false, ba)), "nested lawful permutation");
        check(!same(variable("x", 0), variable("x", 1)), "binding coordinate identity");
        check(!same(variable("x", -1), variable("y", -1)), "lexical scope identity");
        check(!same(atom("identity=f;type=A"), atom("identity=f;type=B")), "exact payload type");
        check(!same(new Node("JOIN", null, null, -1, ContainerKind.SEQUENCE, false, List.of(a,b)),
                new Node("JOIN", null, null, -1, ContainerKind.SEQUENCE, false, List.of(b,a))),
                "JOIN operand order");
        Node choice = new Node("atom", "a", null, -1, ContainerKind.ONE, false,
                List.of(), List.of(a, b));
        check(same(container(ContainerKind.BAG, true, choice, a),
                container(ContainerKind.BAG, true, a, b)), "augmenting path, not greedy reuse");
        check(!same(container(ContainerKind.BAG, true, choice, a),
                container(ContainerKind.BAG, true, b, b)), "one-to-one alternative matching");

        List<Node> wide = new ArrayList<>();
        for (int i = 0; i < 1200; i++) wide.add(atom("v" + i));
        List<Node> reversed = new ArrayList<>(wide);
        Collections.reverse(reversed);
        try {
            same(container(ContainerKind.SET, true, wide.toArray(Node[]::new)),
                    container(ContainerKind.SET, true, reversed.toArray(Node[]::new)));
            throw new AssertionError("Adversarial comparison must fail closed at its finite budget");
        } catch (IllegalStateException exhausted) {
            check(exhausted.getMessage().contains("finite step budget"), "bounded adversarial matching");
        }
        check(same(ab, ba), "a failed comparison does not poison the next budget");
        System.out.println("RepairProjectionPermutationTest: " + checks + " checks passed");
    }

    private static boolean same(Node left, Node right) throws Exception {
        try {
            return (Boolean) SAME.invoke(null, left, right);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    private static Node atom(String payload) {
        return new Node("atom", payload, null, -1, ContainerKind.ONE, false, List.of());
    }

    private static Node variable(String name, int binding) {
        return new Node("VARIABLE", null, name, binding, ContainerKind.ONE, false, List.of());
    }

    private static Node container(ContainerKind kind, boolean unordered, Node... children) {
        return new Node("container", "typed-head", null, -1, kind, unordered, List.of(children));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
