package is.fivefivefive.CanDis.core.egraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonicalizes slot names modulo alpha-renaming and declaration-group permutations. */
final class SlotCanonicalizer {
    private static final Set<String> COMMUTATIVE_HEADS = Set.of(
            "BF/AND", "LF/AND", "BOOL/AND",
            "BF/OR", "LF/OR", "BOOL/OR",
            "BE/PLUS", "REL/PLUS", "BE/INTERSECT", "REL/INTERSECT",
            "BE/MUL", "ARITH/MUL", "BE/IPLUS", "ARITH/PLUS",
            "LE/DISJOINT", "LIST/DISJOINT", "BF/EQUALS", "BF/NOT_EQUALS", "BF/IFF");

    private SlotCanonicalizer() {
    }

    static AlloyTerm canonicalize(AlloyTerm term) {
        return canonicalize(term, false);
    }

    static AlloyTerm canonicalize(AlloyTerm term, boolean uncapped) {
        AlloyTerm current = scopeVariables(term, Collections.emptyMap(), new ScopeCounter());
        current = SlotBindingBlocks.normalize(current);
        for (int iteration = 0; uncapped || iteration < 8; iteration++) {
            AlloyTerm next = canonicalizeOnce(current, uncapped);
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
        return current;
    }

    private static AlloyTerm scopeVariables(
            AlloyTerm term,
            Map<String, String> environment,
            ScopeCounter counter) {
        if (term.isVariable()) {
            String scoped = environment.get(term.atom());
            return scoped == null ? term : term.renameVariable(scoped);
        }
        if (term.children().isEmpty()) {
            return term;
        }
        if (isBinder(term)) {
            Map<String, String> local = new HashMap<>(environment);
            List<AlloyTerm> children = new ArrayList<>(term.children().size());
            for (AlloyTerm child : term.children()) {
                if (child.head().startsWith("DECL/")) {
                    children.add(scopeDeclaration(child, local, counter));
                } else {
                    children.add(scopeVariables(child, local, counter));
                }
            }
            return term.withChildren(children);
        }
        if ("LetExpr".equals(term.head()) && term.children().size() >= 3) {
            List<AlloyTerm> children = new ArrayList<>(term.children());
            AlloyTerm variable = term.children().get(0);
            children.set(1, scopeVariables(term.children().get(1), environment, counter));
            Map<String, String> local = new HashMap<>(environment);
            if (variable.isVariable()) {
                String scoped = "@scope:" + counter.next++;
                children.set(0, variable.renameVariable(scoped));
                local.put(variable.atom(), scoped);
            }
            for (int i = 2; i < children.size(); i++) {
                children.set(i, scopeVariables(term.children().get(i), local, counter));
            }
            return term.withChildren(children);
        }
        List<AlloyTerm> children = new ArrayList<>(term.children().size());
        for (AlloyTerm child : term.children()) {
            children.add(scopeVariables(child, environment, counter));
        }
        return term.withChildren(children);
    }

    private static AlloyTerm scopeDeclaration(
            AlloyTerm declaration,
            Map<String, String> environment,
            ScopeCounter counter) {
        List<AlloyTerm> variables = new ArrayList<>();
        List<AlloyTerm> domains = new ArrayList<>();
        for (AlloyTerm child : declaration.children()) {
            if (child.isVariable()) {
                variables.add(child);
            } else {
                domains.add(scopeVariables(child, environment, counter));
            }
        }
        List<AlloyTerm> children = new ArrayList<>(declaration.children().size());
        for (AlloyTerm variable : variables) {
            String scoped = "@scope:" + counter.next++;
            children.add(variable.renameVariable(scoped));
            environment.put(variable.atom(), scoped);
        }
        children.addAll(domains);
        return declaration.withChildren(children);
    }

    private static boolean isBinder(AlloyTerm term) {
        return "PREDICATE".equals(term.head())
                || term.head().startsWith("QF/")
                || term.head().startsWith("QE/");
    }

    private static AlloyTerm canonicalizeOnce(AlloyTerm term, boolean uncapped) {
        AlloyTerm[] best = { null };
        SlotPermutationSearch.forEach(term, uncapped, names -> {
            AlloyTerm candidate = sortCommutative(rename(term, names));
            if (best[0] == null || candidate.compareTo(best[0]) < 0) best[0] = candidate;
        });
        return best[0] == null
                ? sortCommutative(rename(term, Collections.emptyMap()))
                : best[0];
    }

    private static AlloyTerm rename(AlloyTerm term, Map<String, String> boundNames) {
        return rename(term, boundNames, new LinkedHashMap<>());
    }

    private static AlloyTerm rename(
            AlloyTerm term,
            Map<String, String> boundNames,
            Map<String, String> freeNames) {
        if (term.isVariable()) {
            String name = boundNames.get(term.atom());
            if (name == null) {
                name = freeNames.computeIfAbsent(term.atom(), ignored -> "@free:" + freeNames.size());
            }
            return term.renameVariable(name);
        }
        if (term.children().isEmpty()) {
            return term;
        }
        List<AlloyTerm> children = new ArrayList<>(term.children().size());
        for (AlloyTerm child : term.children()) {
            children.add(rename(child, boundNames, freeNames));
        }
        if (term.head().startsWith("DECL/")) {
            int variables = 0;
            while (variables < children.size() && children.get(variables).isVariable()) {
                variables++;
            }
            if (variables > 1) {
                List<AlloyTerm> sorted = new ArrayList<>(children.subList(0, variables));
                Collections.sort(sorted);
                for (int i = 0; i < variables; i++) {
                    children.set(i, sorted.get(i));
                }
            }
        }
        if (SlotBindingBlocks.isExchangeBlock(term)) {
            // The domain travels with its renamed binding when independent declarations commute.
            children.subList(0, children.size() - 1).sort(AlloyTerm::compareTo);
        }
        return term.withChildren(children);
    }

    private static AlloyTerm sortCommutative(AlloyTerm term) {
        if (term.children().isEmpty()) {
            return term;
        }
        List<AlloyTerm> children = new ArrayList<>(term.children().size());
        for (AlloyTerm child : term.children()) {
            children.add(sortCommutative(child));
        }
        if (COMMUTATIVE_HEADS.contains(term.head()) && children.size() > 1) {
            sortOperands(children);
        }
        return term.withChildren(children);
    }

    /** Shared orientation for commutative operands in the slotted rewrite/alpha passes. */
    static void sortOperands(List<AlloyTerm> children) {
        Map<AlloyTerm, AlloyTerm> keys = new java.util.IdentityHashMap<>();
        children.sort((left, right) -> {
            int localOrder = keys.computeIfAbsent(left, SlotCanonicalizer::localSlotKey)
                    .compareTo(keys.computeIfAbsent(right, SlotCanonicalizer::localSlotKey));
            // A total tie-break keeps identical operands adjacent for ACI deduplication.
            return localOrder != 0 ? localOrder : left.compareTo(right);
        });
    }

    private static AlloyTerm localSlotKey(AlloyTerm term) {
        return localSlotKey(term, new LinkedHashMap<>());
    }

    private static AlloyTerm localSlotKey(AlloyTerm term, Map<String, String> slots) {
        if (term.isVariable()) {
            return term.renameVariable(slots.computeIfAbsent(
                    term.atom(), ignored -> "@local:" + slots.size()));
        }
        if (term.children().isEmpty()) {
            return term;
        }
        List<AlloyTerm> children = new ArrayList<>(term.children().size());
        for (AlloyTerm child : term.children()) {
            children.add(localSlotKey(child, slots));
        }
        return term.withChildren(children);
    }

    private static final class ScopeCounter {
        private int next;
    }
}
