package is.fivefivefive.CanDis.core;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import is.fivefivefive.CanDis.WorkBudget;

/** Checked whole-binder alpha transport, not a fixed-descriptor automorphism.
 * Correspondence: DependentBinderTransport.closed_binder_same_environment and
 * invocation_commutes. No certificate or search is constructed here. */
public final class LocalBinderTransport {
    private LocalBinderTransport() { }

    public interface View<N> {
        String operator(N node);
        String variable(N node);
        Object type(N node);
        List<N> children(N node);
        boolean sameHead(N source, N target);
        default List<N> alternatives(N node) { return List.of(); }
        default Map<String, String> invocation(N node, int child) { return Map.of(); }
        default void requireSource(N node) { }
    }

    public static boolean declaration(String operator) {
        return switch (operator) {
            case "GENERICRELDECL", "DISJ", "VAR", "DISJVAR" -> true;
            default -> false;
        };
    }

    private static <N> boolean binder(N node, View<N> view) {
        return switch (view.operator(node)) {
            case "FORALL", "EXISTS", "NO", "ONE", "LONE", "SUM", "COMPREHENSION" ->
                view.children(node).stream().anyMatch(n -> declaration(view.operator(n)));
            default -> false;
        };
    }

    public static String rename(Map<String, String> names, String name) {
        return name == null ? null : names.getOrDefault(name, name);
    }

    /** The finite spelling of p . invocation . p^-1, with identity elsewhere. */
    public static Map<String, String> moveInvocation(
            Map<String, String> invocation, Map<String, String> names) {
        if (invocation.isEmpty()) return invocation;
        Map<String, String> moved = new LinkedHashMap<>();
        invocation.forEach((key, value) -> moved.put(rename(names, key), rename(names, value)));
        return Map.copyOf(moved);
    }

    public static <N> void requireClosed(N root, Set<String> names, View<N> view) {
        require(binder(root, view), "local binder missing");
        Set<String> declared = new HashSet<>();
        for (N child : view.children(root)) {
            if (!declaration(view.operator(child))) continue;
            List<N> parts = view.children(child);
            require(parts.size() >= 2, "empty declaration");
            for (N variable : parts.subList(1, parts.size())) {
                String name = view.variable(variable);
                require(name != null && declared.add(name), "duplicate/non-variable declaration");
            }
        }
        require(declared.equals(names), "transport differs from declared local slots");
        scope(root, names, Set.of(), Map.of(), view, true);
    }

    private static Map<String, String> compose(Map<String, String> edge, Map<String, String> ambient) {
        if (edge.isEmpty()) return ambient;
        Map<String, String> result = new LinkedHashMap<>(ambient);
        edge.forEach((key, value) -> result.put(key, rename(ambient, value)));
        return result;
    }

    private static <N> void scope(N node, Set<String> names, Set<String> active,
            Map<String, String> ambient, View<N> view, boolean root) {
        WorkBudget.charge(1);
        view.requireSource(node);
        String variable = view.variable(node);
        if (variable != null) {
            String effective = rename(ambient, variable);
            require(!names.contains(effective) || active.contains(effective), "read precedes declaration");
            return;
        }
        List<N> children = view.children(node);
        Set<String> prefix = new HashSet<>(active);
        boolean local = binder(node, view);
        for (int i = 0; i < children.size(); i++) {
            N child = children.get(i);
            Map<String, String> invocation = compose(view.invocation(node, i), ambient);
            if (local && declaration(view.operator(child))) {
                view.requireSource(child);
                List<N> parts = view.children(child);
                require(parts.size() >= 2, "empty declaration");
                scope(parts.get(0), names, prefix, compose(view.invocation(child, 0), invocation), view, false);
                for (int j = 1; j < parts.size(); j++) {
                    N slot = parts.get(j);
                    String raw = view.variable(slot);
                    require(raw != null, "non-variable slot");
                    String name = rename(compose(view.invocation(child, j), invocation), raw);
                    require(root || !names.contains(name), "nested slot captures transported name");
                    require(prefix.add(name), "slot shadows existing binding");
                }
            } else {
                scope(child, names, prefix, invocation, view, false);
            }
        }
    }

    public static <N> void requireRenamed(N source, N target,
            Map<String, String> names, View<N> view) {
        require(names.keySet().equals(new HashSet<>(names.values())), "not a local bijection");
        requireClosed(source, names.keySet(), view);
        Map<String, Object> types = new LinkedHashMap<>();
        for (N child : view.children(source)) {
            if (!declaration(view.operator(child))) continue;
            for (N variable : view.children(child).subList(1, view.children(child).size()))
                types.put(view.variable(variable), view.type(variable));
        }
        for (var entry : names.entrySet())
            require(types.get(entry.getKey()) != null
                    && Objects.equals(types.get(entry.getKey()), types.get(entry.getValue())), "slot type changed");
        renamed(source, target, names, view);
    }

    private static <N> void renamed(N source, N target, Map<String, String> names, View<N> view) {
        WorkBudget.charge(1);
        view.requireSource(source);
        view.requireSource(target);
        require(view.sameHead(source, target), "head/type/source metadata changed");
        require(Objects.equals(rename(names, view.variable(source)), view.variable(target)), "read transport differs");
        List<N> before = view.children(source), after = view.children(target);
        require(before.size() == after.size(), "child occurrence count changed");
        for (int i = 0; i < before.size(); i++) {
            require(moveInvocation(view.invocation(source, i), names).equals(view.invocation(target, i)),
                    "invocation does not commute");
            renamed(before.get(i), after.get(i), names, view);
        }
        List<N> oldAlternatives = view.alternatives(source), newAlternatives = view.alternatives(target);
        require(oldAlternatives.size() == newAlternatives.size(), "nested alternative count changed");
        for (int i = 0; i < oldAlternatives.size(); i++)
            renamed(oldAlternatives.get(i), newAlternatives.get(i), names, view);
    }

    private static void require(boolean valid, String detail) {
        if (!valid) throw new IllegalArgumentException("LOCAL_BINDER_TRANSPORT: " + detail);
    }
}
