package live;

import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.parser.CompModule;
import org.json.JSONArray;
import org.json.JSONObject;
import is.fivefivefive.CanDis.WorkBudget;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trusted deployment policy for selected practice slots, independent of spelling
 * in the learner's body. The absent field retains the historical engine contract.
 * Once present, every policy name must resolve to exactly one local predicate in
 * this same parsed Alloy world. Imported functions with the same basename do not
 * gain or lose authority through a lexical name match.
 */
final class PracticeDependencyGuard {
    static final int MAX_BLOCKED = 356; // 100 base practices and 256 C aliases.
    private PracticeDependencyGuard() { }

    static List<String> read(JSONObject request, String target) {
        if (!request.has("blockedPredicates")) return null;
        Object value = request.get("blockedPredicates");
        if (!(value instanceof JSONArray names) || names.length() > MAX_BLOCKED)
            throw new InvalidSelection();
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : names) {
            if (!(item instanceof String name) || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                    || name.equals(target) || !seen.add(name)) throw new InvalidSelection();
            result.add(name);
        }
        return List.copyOf(result);
    }

    static void check(CompModule world, String targetName, List<String> names) {
        if (names == null) return;
        Set<String> required = new HashSet<>(names);
        required.add(targetName);
        Map<String, Func> resolved = new HashMap<>();
        // One root inventory pass, independent of the number of blocked slots.
        for (Func function : world.getAllFunc()) {
            WorkBudget.charge(1);
            String label = function.label.startsWith("this/") ? function.label.substring(5) : function.label;
            if (!required.contains(label)) continue;
            if (!function.isPred || resolved.putIfAbsent(label, function) != null) throw new InvalidSelection();
        }
        if (!resolved.keySet().equals(required)) throw new InvalidSelection();
        Func target = resolved.get(targetName);
        Set<Func> blocked = identitySet();
        for (String name : names) blocked.add(resolved.get(name));

        Set<Func> visited = identitySet();
        ArrayDeque<Func> pending = new ArrayDeque<>();
        pending.add(target);
        while (!pending.isEmpty()) {
            WorkBudget.charge(1);
            Func current = pending.removeFirst();
            if (blocked.contains(current)) throw new UnsolvedCall();
            if (!visited.add(current)) continue;
            // This includes actual calls in function bodies, call arguments,
            // quantifier/let expressions, argument domains and return domains.
            for (Func called : BehaviorFeedback.calls(current)) pending.addLast(called);
        }
        // Facts/domain dependence and target recursion retain the existing live
        // semantic boundary. Dormant incoming helpers do not belong to this walk.
        if (BehaviorFeedback.hasUnsafeDependencies(world, target)) throw new UnsafeContext();
    }

    private static Set<Func> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    static final class InvalidSelection extends IllegalArgumentException { }
    static final class UnsolvedCall extends IllegalArgumentException { }
    static final class UnsafeContext extends IllegalArgumentException { }
}
