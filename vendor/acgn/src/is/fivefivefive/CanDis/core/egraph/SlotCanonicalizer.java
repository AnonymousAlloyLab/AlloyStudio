package is.fivefivefive.CanDis.core.egraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonicalizes slot names modulo alpha-renaming and declaration-group permutations. */
final class SlotCanonicalizer {
    private static final int MAX_PERMUTATIONS = 720;
    private static final Set<String> COMMUTATIVE_HEADS = Set.of(
            "BF/AND", "LF/AND", "BOOL/AND",
            "BF/OR", "LF/OR", "BOOL/OR",
            "BE/PLUS", "REL/PLUS", "BE/INTERSECT", "REL/INTERSECT",
            "BE/MUL", "ARITH/MUL", "BE/IPLUS", "ARITH/PLUS",
            "LE/DISJOINT", "LIST/DISJOINT", "BF/EQUALS", "BF/IFF");

    private SlotCanonicalizer() {
    }

    static AlloyTerm canonicalize(AlloyTerm term) {
        AlloyTerm current = scopeVariables(term, Collections.emptyMap(), new ScopeCounter());
        for (int iteration = 0; iteration < 8; iteration++) {
            AlloyTerm next = canonicalizeOnce(current);
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
            return scoped == null ? term : AlloyTerm.atom("VAR", scoped);
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
                children.set(0, AlloyTerm.atom("VAR", scoped));
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
            children.add(AlloyTerm.atom("VAR", scoped));
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

    private static AlloyTerm canonicalizeOnce(AlloyTerm term) {
        List<List<String>> groups = new ArrayList<>();
        collectDeclarationGroups(term, groups);
        Search search = new Search(term, groups);
        search.visitGroup(0, new HashMap<>());
        return search.best == null
                ? sortCommutative(rename(term, Collections.emptyMap()))
                : search.best;
    }

    private static void collectDeclarationGroups(AlloyTerm term, List<List<String>> groups) {
        for (AlloyTerm child : term.children()) {
            if (isSymmetricFormulaBinder(term) && child.head().startsWith("DECL/")) {
                List<String> names = new ArrayList<>();
                for (AlloyTerm declarationChild : child.children()) {
                    if (declarationChild.isVariable()) {
                        names.add(declarationChild.atom());
                    }
                }
                if (names.size() > 1) {
                    groups.add(names);
                }
            }
            collectDeclarationGroups(child, groups);
        }
    }

    private static boolean isSymmetricFormulaBinder(AlloyTerm term) {
        switch (term.head()) {
            case "QF/ALL":
            case "QF/SOME":
            case "QF/NO":
            case "QF/ONE":
            case "QF/LONE":
            case "QF/NOTONE":
            case "QF/NOTLONE":
                return true;
            default:
                return false;
        }
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
            return AlloyTerm.atom("VAR", name);
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
            Map<AlloyTerm, AlloyTerm> keys = new java.util.IdentityHashMap<>();
            children.sort((left, right) -> keys.computeIfAbsent(left, SlotCanonicalizer::localSlotKey)
                    .compareTo(keys.computeIfAbsent(right, SlotCanonicalizer::localSlotKey)));
        }
        return term.withChildren(children);
    }

    private static AlloyTerm localSlotKey(AlloyTerm term) {
        return localSlotKey(term, new LinkedHashMap<>());
    }

    private static AlloyTerm localSlotKey(AlloyTerm term, Map<String, String> slots) {
        if (term.isVariable()) {
            return AlloyTerm.atom("VAR", slots.computeIfAbsent(
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

    private static List<int[]> permutations(int size, int remainingBudget) {
        List<int[]> output = new ArrayList<>();
        int[] permutation = new int[size];
        for (int i = 0; i < size; i++) {
            permutation[i] = i;
        }
        do {
            output.add(permutation.clone());
        } while (output.size() < remainingBudget && nextPermutation(permutation));
        return output;
    }

    private static boolean nextPermutation(int[] values) {
        int pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) {
            pivot--;
        }
        if (pivot < 0) {
            return false;
        }
        int successor = values.length - 1;
        while (values[successor] <= values[pivot]) {
            successor--;
        }
        int swap = values[pivot];
        values[pivot] = values[successor];
        values[successor] = swap;
        for (int left = pivot + 1, right = values.length - 1; left < right; left++, right--) {
            swap = values[left];
            values[left] = values[right];
            values[right] = swap;
        }
        return true;
    }

    private static final class Search {
        private final AlloyTerm input;
        private final List<List<String>> groups;
        private int candidates;
        private AlloyTerm best;

        private Search(AlloyTerm input, List<List<String>> groups) {
            this.input = input;
            this.groups = groups;
        }

        private void visitGroup(int index, Map<String, String> names) {
            if (candidates >= MAX_PERMUTATIONS) {
                return;
            }
            if (index == groups.size()) {
                candidates++;
                AlloyTerm candidate = sortCommutative(rename(input, names));
                if (best == null || candidate.compareTo(best) < 0) {
                    best = candidate;
                }
                return;
            }
            List<String> group = groups.get(index);
            Set<String> uniqueNames = new LinkedHashSet<>(group);
            List<String> orderedNames = new ArrayList<>(uniqueNames);
            int remaining = Math.max(1, MAX_PERMUTATIONS - candidates);
            for (int[] permutation : permutations(orderedNames.size(), remaining)) {
                Map<String, String> extended = new HashMap<>(names);
                for (int i = 0; i < orderedNames.size(); i++) {
                    extended.put(orderedNames.get(i), "@group:" + index + ':' + permutation[i]);
                }
                visitGroup(index + 1, extended);
                if (candidates >= MAX_PERMUTATIONS) {
                    return;
                }
            }
        }
    }

    private static final class ScopeCounter {
        private int next;
    }
}
