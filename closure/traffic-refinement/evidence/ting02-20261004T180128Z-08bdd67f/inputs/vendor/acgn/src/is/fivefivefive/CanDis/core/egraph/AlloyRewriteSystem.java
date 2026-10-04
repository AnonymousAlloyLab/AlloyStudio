package is.fivefivefive.CanDis.core.egraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import is.fivefivefive.CanDis.theory.LeanVerifiedRewrite;

/** Shared, terminating orientation of the Alloy equivalences used by the baselines. */
final class AlloyRewriteSystem {
    static final int MAX_ITERATIONS = 32;
    static final String RULE_SET_VERSION = "canonical-equivalences-v3-explicit-laws";
    private static final List<String> RULE_NAMES = List.of(
            "operator aliases",
            "NOOP elimination",
            "capture-avoiding let beta reduction",
            "implication elimination",
            "iff elimination",
            "formula ITE elimination",
            "boolean constant negation and double negation",
            "De Morgan",
            "atomic negation duals",
            "temporal negation duals",
            "quantifier negation duals",
            "no-to-all-not quantifier expansion",
            "empty quantifier domains",
            "constant quantifier bodies",
            "safe existential-conjunction prenex",
            "safe universal-disjunction prenex",
            "associativity",
            "commutativity",
            "idempotence",
            "boolean identities and annihilators",
            "boolean complements",
            "membership in none/univ",
            "relational union with none",
            "relational intersection with none");

    enum ArityMode {
        FIXED,
        VARIADIC
    }

    private static final Set<String> ACI = Set.of(
            "BOOL/AND", "BOOL/OR", "REL/PLUS", "REL/INTERSECT");
    private static final Set<String> ASSOCIATIVE = Set.of(
            "BOOL/AND", "BOOL/OR", "REL/PLUS", "REL/INTERSECT");
    private static final Set<String> COMMUTATIVE = Set.of(
            "BOOL/AND", "BOOL/OR", "REL/PLUS", "REL/INTERSECT",
            "ARITH/MUL", "ARITH/PLUS", "LIST/DISJOINT",
            "BF/EQUALS", "BF/NOT_EQUALS");

    private AlloyRewriteSystem() {
    }

    static List<String> ruleNames() {
        return RULE_NAMES;
    }

    static Pass rewriteOnce(AlloyTerm input) {
        return rewriteOnce(input, ArityMode.VARIADIC);
    }

    static Pass rewriteOnce(AlloyTerm input, ArityMode arityMode) {
        Counter counter = new Counter();
        AlloyTerm output = rewriteTree(
                input, counter, arityMode, Collections.emptyMap());
        return new Pass(output, counter.changes);
    }

    private static AlloyTerm rewriteTree(
            AlloyTerm input,
            Counter counter,
            ArityMode arityMode,
            Map<String, Boolean> positiveBindings) {
        if (isScopedBinder(input)) {
            return rewriteScopedTree(input, counter, arityMode, positiveBindings);
        }
        List<AlloyTerm> children = input.children();
        List<AlloyTerm> rewrittenChildren = new ArrayList<>(children.size());
        boolean childChanged = false;
        for (AlloyTerm child : children) {
            AlloyTerm rewritten = rewriteTree(
                    child, counter, arityMode, positiveBindings);
            rewrittenChildren.add(rewritten);
            childChanged |= rewritten != child;
        }
        AlloyTerm current = childChanged ? input.withChildren(rewrittenChildren) : input;
        AlloyTerm rewritten = rewriteNode(current, arityMode, positiveBindings);
        if (!rewritten.equals(current)) {
            counter.changes++;
            return rewritten;
        }
        return current;
    }

    private static AlloyTerm rewriteScopedTree(
            AlloyTerm input,
            Counter counter,
            ArityMode arityMode,
            Map<String, Boolean> outerBindings) {
        Map<String, Boolean> scopedBindings = new HashMap<>(outerBindings);
        List<AlloyTerm> rewrittenChildren = new ArrayList<>(input.children().size());
        boolean childChanged = false;
        for (AlloyTerm child : input.children()) {
            AlloyTerm rewritten = rewriteTree(
                    child, counter, arityMode, scopedBindings);
            rewrittenChildren.add(rewritten);
            childChanged |= rewritten != child;
            if (isDeclaration(rewritten)) {
                addDeclarationBindings(rewritten, scopedBindings);
            }
        }
        AlloyTerm current = childChanged
                ? input.withChildren(rewrittenChildren)
                : input;
        AlloyTerm rewritten = rewriteNode(current, arityMode, outerBindings);
        if (!rewritten.equals(current)) {
            counter.changes++;
            return rewritten;
        }
        return current;
    }

    private static boolean isScopedBinder(AlloyTerm term) {
        return "PREDICATE".equals(term.head())
                || term.head().startsWith("QF/")
                || term.head().startsWith("QE/");
    }

    private static boolean isDeclaration(AlloyTerm term) {
        return term.head().startsWith("DECL/");
    }

    private static void addDeclarationBindings(
            AlloyTerm declaration,
            Map<String, Boolean> target) {
        List<AlloyTerm> children = declaration.children();
        int variableCount = 0;
        while (variableCount < children.size()
                && children.get(variableCount).isVariable()) {
            variableCount++;
        }
        if (variableCount == 0 || variableCount == children.size()) {
            return;
        }
        boolean positive = isPositiveBindingDomain(
                children.get(children.size() - 1));
        for (int index = 0; index < variableCount; index++) {
            target.put(children.get(index).atom(), positive);
        }
    }

    private static boolean isPositiveBindingDomain(AlloyTerm domain) {
        AlloyTerm current = domain;
        while ("UE/NOOP".equals(canonicalHead(current.head()))
                && current.children().size() == 1) {
            current = current.children().get(0);
        }
        String head = canonicalHead(current.head());
        if ("UE/SETOF".equals(head) || "UE/LONE".equals(head)) {
            return false;
        }
        return "UE/SOME".equals(head)
                || "UE/ONE".equals(head)
                || !head.startsWith("UE/");
    }

    @LeanVerifiedRewrite({
            "R0-CORE-001", "R0-CORE-002", "R0-CORE-003", "R0-CORE-004",
            "R0-CORE-005", "R0-CORE-006", "R0-CORE-013", "R0-CORE-014",
            "R0-CORE-015", "R0-CORE-016", "R0-CORE-017", "R0-CORE-018",
            "R0-CORE-019", "R0-CORE-020", "R0-CORE-021", "R0-CORE-022",
            "R0-CORE-023", "R0-CORE-024"
    })
    private static AlloyTerm rewriteNode(
            AlloyTerm input,
            ArityMode arityMode,
            Map<String, Boolean> positiveBindings) {
        String canonicalHead = canonicalHead(input.head());
        AlloyTerm current = canonicalHead.equals(input.head())
                ? input
                : newTerm(canonicalHead, input.atom(), input.children());

        if ("CONST".equals(current.head()) && !current.atom().equals(current.atom().toLowerCase())) {
            return AlloyTerm.atom("CONST", current.atom().toLowerCase());
        }
        if ("UE/NOOP".equals(current.head()) && current.children().size() == 1) {
            return current.children().get(0);
        }
        if ("LetExpr".equals(current.head()) && current.children().size() >= 3) {
            AlloyTerm variable = current.children().get(0);
            if (variable.isVariable()) {
                AlloyTerm bound = current.children().get(1);
                AlloyTerm body = unwrapBody(current.children().get(current.children().size() - 1));
                return substitute(body, variable.atom(), bound);
            }
        }
        if ("BOOL/IMPLIES".equals(current.head()) && current.children().size() == 2) {
            return AlloyTerm.node("BOOL/OR", not(current.children().get(0)), current.children().get(1));
        }
        if ("BOOL/IFF".equals(current.head()) && current.children().size() == 2) {
            AlloyTerm left = current.children().get(0);
            AlloyTerm right = current.children().get(1);
            return AlloyTerm.node("BOOL/AND",
                    AlloyTerm.node("BOOL/OR", not(left), right),
                    AlloyTerm.node("BOOL/OR", not(right), left));
        }
        if ("ITE/FORMULA".equals(current.head()) && current.children().size() == 3) {
            AlloyTerm condition = current.children().get(0);
            AlloyTerm thenClause = current.children().get(1);
            AlloyTerm elseClause = current.children().get(2);
            return AlloyTerm.node("BOOL/OR",
                    AlloyTerm.node("BOOL/AND", condition, thenClause),
                    AlloyTerm.node("BOOL/AND", not(condition), elseClause));
        }
        if ("BOOL/NOT".equals(current.head()) && current.children().size() == 1) {
            return rewriteNot(current.children().get(0));
        }
        if (current.children().size() == 1
                && isConstant(current.children().get(0), "none")) {
            switch (current.head()) {
                case "UF/SOME":
                case "UF/ONE":
                    return bool(false);
                case "UF/NO":
                case "UF/LONE":
                    return bool(true);
                default:
                    break;
            }
        }
        if (current.head().startsWith("QF/")) {
            AlloyTerm empty = emptyDomainResult(current);
            if (empty != null) {
                return empty;
            }
            AlloyTerm constantBody = constantBodyResult(current);
            if (constantBody != null) {
                return constantBody;
            }
            if ("QF/NO".equals(current.head())) {
                return rewriteQuantifier(current, "QF/ALL", true);
            }
        }
        if ("BF/IN".equals(current.head()) && current.children().size() == 2) {
            AlloyTerm value = current.children().get(0);
            AlloyTerm domain = current.children().get(1);
            if (isConstant(domain, "none")) {
                if (isConstant(value, "none")) {
                    return bool(true);
                }
                if (isStaticallyNonempty(value, positiveBindings)) {
                    return bool(false);
                }
            }
            if (isConstant(domain, "univ")) {
                return bool(true);
            }
        }
        if ("BF/NOT_IN".equals(current.head()) && current.children().size() == 2) {
            AlloyTerm value = current.children().get(0);
            AlloyTerm domain = current.children().get(1);
            if (isConstant(domain, "none")) {
                if (isConstant(value, "none")) {
                    return bool(false);
                }
                if (isStaticallyNonempty(value, positiveBindings)) {
                    return bool(true);
                }
            }
            if (isConstant(domain, "univ")) {
                return bool(false);
            }
        }
        if (ASSOCIATIVE.contains(current.head())) {
            List<AlloyTerm> flattened = new ArrayList<>();
            flatten(current.head(), current, flattened);
            current = AlloyTerm.node(current.head(), flattened);
        }
        if (COMMUTATIVE.contains(current.head())) {
            List<AlloyTerm> sorted = new ArrayList<>(current.children());
            Collections.sort(sorted);
            current = current.withChildren(sorted);
        }
        if (ACI.contains(current.head())) {
            List<AlloyTerm> unique = deduplicate(current.children());
            current = current.withChildren(unique);
        }
        if ("REL/PLUS".equals(current.head())) {
            List<AlloyTerm> kept = withoutConstant(current.children(), "none");
            if (kept.isEmpty()) {
                return constant("none");
            }
            if (kept.size() == 1) {
                return kept.get(0);
            }
            current = current.withChildren(kept);
        }
        if ("REL/INTERSECT".equals(current.head()) && containsConstant(current.children(), "none")) {
            return constant("none");
        }
        if ("REL/MINUS".equals(current.head()) && current.children().size() == 2
                && current.children().get(0).equals(current.children().get(1))) {
            return constant("none");
        }
        if ("BOOL/AND".equals(current.head())) {
            if (containsConstant(current.children(), "false") || containsComplement(current.children())) {
                return bool(false);
            }
            List<AlloyTerm> kept = withoutConstant(current.children(), "true");
            AlloyTerm simplified = finishVariadic(current.head(), kept, true);
            if (!"BOOL/AND".equals(simplified.head())) {
                return simplified;
            }
            current = simplified;
        }
        if ("BOOL/OR".equals(current.head())) {
            if (containsConstant(current.children(), "true") || containsComplement(current.children())) {
                return bool(true);
            }
            List<AlloyTerm> kept = withoutConstant(current.children(), "false");
            AlloyTerm simplified = finishVariadic(current.head(), kept, false);
            if (!"BOOL/OR".equals(simplified.head())) {
                return simplified;
            }
            current = simplified;
        }
        if (ASSOCIATIVE.contains(current.head()) && current.children().size() == 1) {
            return current.children().get(0);
        }
        AlloyTerm prenexed = safePrenex(current);
        if (!prenexed.equals(current)) {
            return prenexed;
        }
        return encodeArity(current, arityMode);
    }

    @LeanVerifiedRewrite({
            "R0-CORE-007", "R0-CORE-008", "R0-CORE-009", "R0-CORE-010",
            "R0-CORE-011"
    })
    private static AlloyTerm rewriteNot(AlloyTerm child) {
        if (isConstant(child, "true")) {
            return bool(false);
        }
        if (isConstant(child, "false")) {
            return bool(true);
        }
        if ("BOOL/NOT".equals(child.head()) && child.children().size() == 1) {
            return child.children().get(0);
        }
        if (("BOOL/AND".equals(child.head()) || "BOOL/OR".equals(child.head()))) {
            String opposite = "BOOL/AND".equals(child.head()) ? "BOOL/OR" : "BOOL/AND";
            List<AlloyTerm> negated = new ArrayList<>(child.children().size());
            for (AlloyTerm item : child.children()) {
                negated.add(not(item));
            }
            return AlloyTerm.node(opposite, negated);
        }
        String atomicDual = atomicNegationDual(child.head());
        if (atomicDual != null) {
            return AlloyTerm.node(atomicDual, child.children());
        }
        String temporalDual = temporalNegationDual(child.head());
        if (temporalDual != null) {
            List<AlloyTerm> negated = new ArrayList<>(child.children().size());
            for (AlloyTerm item : child.children()) {
                negated.add(not(item));
            }
            return AlloyTerm.node(temporalDual, negated);
        }
        if (child.head().startsWith("QF/")) {
            switch (child.head()) {
                case "QF/ALL":
                    return rewriteQuantifier(child, "QF/SOME", true);
                case "QF/SOME":
                    return rewriteQuantifier(child, "QF/ALL", true);
                case "QF/NO":
                    return rewriteQuantifier(child, "QF/SOME", false);
                case "QF/ONE":
                    return rewriteQuantifier(child, "QF/NOTONE", false);
                case "QF/LONE":
                    return rewriteQuantifier(child, "QF/NOTLONE", false);
                case "QF/NOTONE":
                    return rewriteQuantifier(child, "QF/ONE", false);
                case "QF/NOTLONE":
                    return rewriteQuantifier(child, "QF/LONE", false);
                default:
                    break;
            }
        }
        return not(child);
    }

    private static String atomicNegationDual(String head) {
        switch (head) {
            case "BF/EQUALS":
                return "BF/NOT_EQUALS";
            case "BF/NOT_EQUALS":
                return "BF/EQUALS";
            case "BF/GT":
                return "BF/LTE";
            case "BF/GTE":
                return "BF/LT";
            case "BF/IN":
                return "BF/NOT_IN";
            case "BF/LT":
                return "BF/GTE";
            case "BF/LTE":
                return "BF/GT";
            case "BF/NOT_GT":
                return "BF/GT";
            case "BF/NOT_GTE":
                return "BF/GTE";
            case "BF/NOT_IN":
                return "BF/IN";
            case "BF/NOT_LT":
                return "BF/LT";
            case "BF/NOT_LTE":
                return "BF/LTE";
            case "UF/SOME":
                return "UF/NO";
            case "UF/NO":
                return "UF/SOME";
            default:
                return null;
        }
    }

    private static String temporalNegationDual(String head) {
        switch (head) {
            case "UF/ALWAYS":
                return "UF/EVENTUALLY";
            case "UF/EVENTUALLY":
                return "UF/ALWAYS";
            case "UF/HISTORICALLY":
                return "UF/ONCE";
            case "UF/ONCE":
                return "UF/HISTORICALLY";
            case "BF/UNTIL":
                return "BF/RELEASES";
            case "BF/RELEASES":
                return "BF/UNTIL";
            case "BF/SINCE":
                return "BF/TRIGGERED";
            case "BF/TRIGGERED":
                return "BF/SINCE";
            default:
                return null;
        }
    }

    @LeanVerifiedRewrite({"R0-CORE-011", "R0-CORE-012", "R0-CORE-013", "R0-CORE-014"})
    private static AlloyTerm rewriteQuantifier(AlloyTerm quantifier, String head, boolean negateBody) {
        List<AlloyTerm> children = new ArrayList<>(quantifier.children());
        if (!children.isEmpty() && negateBody) {
            int bodyIndex = children.size() - 1;
            AlloyTerm body = children.get(bodyIndex);
            if ("Body".equals(body.head()) && body.children().size() == 1) {
                children.set(bodyIndex, AlloyTerm.node("Body", not(body.children().get(0))));
            } else {
                children.set(bodyIndex, not(body));
            }
        }
        return AlloyTerm.node(head, children);
    }

    private static AlloyTerm emptyDomainResult(AlloyTerm quantifier) {
        boolean empty = false;
        for (AlloyTerm child : quantifier.children()) {
            if (child.head().startsWith("DECL/") && !child.children().isEmpty()
                    && isEmptyDomain(child.children().get(child.children().size() - 1))) {
                empty = true;
                break;
            }
        }
        if (!empty) {
            return null;
        }
        switch (quantifier.head()) {
            case "QF/ALL":
            case "QF/NO":
            case "QF/LONE":
            case "QF/NOTONE":
                return bool(true);
            case "QF/SOME":
            case "QF/ONE":
            case "QF/NOTLONE":
                return bool(false);
            default:
                return null;
        }
    }

    private static AlloyTerm constantBodyResult(AlloyTerm quantifier) {
        if (quantifier.children().isEmpty()) {
            return null;
        }
        AlloyTerm body = quantifier.children().get(quantifier.children().size() - 1);
        if ("Body".equals(body.head()) && body.children().size() == 1) {
            body = body.children().get(0);
        }
        if (isConstant(body, "true") && "QF/ALL".equals(quantifier.head())) {
            return bool(true);
        }
        if (!isConstant(body, "false")) {
            return null;
        }
        switch (quantifier.head()) {
            case "QF/SOME":
            case "QF/ONE":
            case "QF/NOTLONE":
                return bool(false);
            case "QF/NO":
            case "QF/LONE":
            case "QF/NOTONE":
                return bool(true);
            default:
                return null;
        }
    }

    private static boolean isEmptyDomain(AlloyTerm term) {
        if (isConstant(term, "none")) {
            return true;
        }
        if (term.children().size() != 1) {
            return false;
        }
        if ("UE/NOOP".equals(term.head())) {
            return isEmptyDomain(term.children().get(0));
        }
        if ("UE/SETOF".equals(term.head()) || "UE/LONE".equals(term.head())) {
            return false;
        }
        return ("UE/SOME".equals(term.head())
                        || "UE/ONE".equals(term.head()))
                && isEmptyDomain(term.children().get(0));
    }

    private static boolean isStaticallyNonempty(
            AlloyTerm term,
            Map<String, Boolean> positiveBindings) {
        return term.isVariable()
                && Boolean.TRUE.equals(positiveBindings.get(term.atom()));
    }

    private static AlloyTerm substitute(AlloyTerm term, String variable, AlloyTerm replacement) {
        Set<String> replacementNames = new HashSet<>();
        collectVariableNames(replacement, replacementNames);
        Set<String> usedNames = new HashSet<>(replacementNames);
        collectVariableNames(term, usedNames);
        return substitute(term, variable, replacement, replacementNames, usedNames,
                new HashMap<>(), true, new int[] { 0 });
    }

    private static AlloyTerm substitute(
            AlloyTerm term,
            String variable,
            AlloyTerm replacement,
            Set<String> replacementNames,
            Set<String> usedNames,
            Map<String, String> renames,
            boolean substitutionVisible,
            int[] nextFresh) {
        if (term.isVariable()) {
            String renamed = renames.getOrDefault(term.atom(), term.atom());
            if (substitutionVisible && renamed.equals(variable)) {
                return replacement;
            }
            return renamed.equals(term.atom()) ? term : AlloyTerm.atom("VAR", renamed);
        }
        if (term.children().isEmpty()) {
            return term;
        }
        if ("LetExpr".equals(term.head()) && term.children().size() >= 3) {
            List<AlloyTerm> children = new ArrayList<>(term.children().size());
            AlloyTerm binder = term.children().get(0);
            AlloyTerm rewrittenBound = substitute(term.children().get(1), variable, replacement,
                    replacementNames, usedNames, renames, substitutionVisible, nextFresh);
            Map<String, String> localRenames = new HashMap<>(renames);
            boolean bodyVisible = substitutionVisible;
            if (binder.isVariable()) {
                String original = binder.atom();
                String renamed = renames.getOrDefault(original, original);
                if (substitutionVisible && replacementNames.contains(renamed)) {
                    renamed = freshName(usedNames, nextFresh);
                }
                localRenames.put(original, renamed);
                children.add(AlloyTerm.atom("VAR", renamed));
                if (renamed.equals(variable) || original.equals(variable)) {
                    bodyVisible = false;
                }
            } else {
                children.add(substitute(binder, variable, replacement, replacementNames,
                        usedNames, renames, substitutionVisible, nextFresh));
            }
            children.add(rewrittenBound);
            for (int i = 2; i < term.children().size(); i++) {
                children.add(substitute(term.children().get(i), variable, replacement,
                        replacementNames, usedNames, localRenames, bodyVisible, nextFresh));
            }
            return term.withChildren(children);
        }
        if (isBinder(term)) {
            List<AlloyTerm> children = new ArrayList<>(term.children().size());
            Map<String, String> localRenames = new HashMap<>(renames);
            boolean bodyVisible = substitutionVisible;
            for (AlloyTerm child : term.children()) {
                if (!child.head().startsWith("DECL/")) {
                    children.add(substitute(child, variable, replacement, replacementNames,
                            usedNames, localRenames, bodyVisible, nextFresh));
                    continue;
                }
                List<AlloyTerm> declaration = new ArrayList<>(child.children().size());
                List<AlloyTerm> declaredVariables = new ArrayList<>();
                for (AlloyTerm declarationChild : child.children()) {
                    if (declarationChild.isVariable()) {
                        declaredVariables.add(declarationChild);
                    } else {
                        declaration.add(substitute(declarationChild, variable, replacement,
                                replacementNames, usedNames, localRenames, bodyVisible, nextFresh));
                    }
                }
                List<AlloyTerm> renamedVariables = new ArrayList<>(declaredVariables.size());
                for (AlloyTerm declaredVariable : declaredVariables) {
                    String original = declaredVariable.atom();
                    String renamed = localRenames.getOrDefault(original, original);
                    if (bodyVisible && replacementNames.contains(renamed)) {
                        renamed = freshName(usedNames, nextFresh);
                    }
                    localRenames.put(original, renamed);
                    renamedVariables.add(AlloyTerm.atom("VAR", renamed));
                    if (renamed.equals(variable) || original.equals(variable)) {
                        bodyVisible = false;
                    }
                }
                renamedVariables.addAll(declaration);
                children.add(child.withChildren(renamedVariables));
            }
            return term.withChildren(children);
        }
        List<AlloyTerm> children = new ArrayList<>(term.children().size());
        for (AlloyTerm child : term.children()) {
            children.add(substitute(child, variable, replacement, replacementNames,
                    usedNames, renames, substitutionVisible, nextFresh));
        }
        return term.withChildren(children);
    }

    private static boolean isBinder(AlloyTerm term) {
        return "PREDICATE".equals(term.head())
                || term.head().startsWith("QF/")
                || term.head().startsWith("QE/");
    }

    private static void collectVariableNames(AlloyTerm term, Set<String> names) {
        if (term.isVariable()) {
            names.add(term.atom());
        }
        for (AlloyTerm child : term.children()) {
            collectVariableNames(child, names);
        }
    }

    private static String freshName(Set<String> usedNames, int[] nextFresh) {
        String candidate;
        do {
            candidate = "@beta:" + nextFresh[0]++;
        } while (!usedNames.add(candidate));
        return candidate;
    }

    private static AlloyTerm unwrapBody(AlloyTerm term) {
        return "Body".equals(term.head()) && term.children().size() == 1
                ? term.children().get(0)
                : term;
    }

    private static AlloyTerm safePrenex(AlloyTerm term) {
        boolean conjunction = "BOOL/AND".equals(term.head());
        boolean disjunction = "BOOL/OR".equals(term.head());
        if ((!conjunction && !disjunction) || term.children().size() < 2) {
            return term;
        }
        String movableHead = conjunction ? "QF/SOME" : "QF/ALL";
        for (int index = 0; index < term.children().size(); index++) {
            AlloyTerm quantifier = term.children().get(index);
            if (!movableHead.equals(quantifier.head()) || !hasFormulaQuantifierShape(quantifier)) {
                continue;
            }
            Set<String> bound = declaredVariables(quantifier);
            Set<String> outside = new HashSet<>();
            for (int i = 0; i < term.children().size(); i++) {
                if (i != index) {
                    collectVariableNames(term.children().get(i), outside);
                }
            }
            if (!Collections.disjoint(bound, outside)) {
                continue;
            }

            int bodyIndex = quantifier.children().size() - 1;
            AlloyTerm oldBody = quantifier.children().get(bodyIndex);
            List<AlloyTerm> matrixChildren = new ArrayList<>(term.children());
            matrixChildren.set(index, unwrapBody(oldBody));
            AlloyTerm matrix = AlloyTerm.node(term.head(), matrixChildren);
            AlloyTerm wrappedMatrix = "Body".equals(oldBody.head())
                    ? AlloyTerm.node("Body", matrix)
                    : matrix;
            List<AlloyTerm> quantifierChildren = new ArrayList<>(quantifier.children());
            quantifierChildren.set(bodyIndex, wrappedMatrix);
            return AlloyTerm.node(quantifier.head(), quantifierChildren);
        }
        return term;
    }

    private static boolean hasFormulaQuantifierShape(AlloyTerm quantifier) {
        if (quantifier.children().size() < 2) {
            return false;
        }
        int bodyIndex = quantifier.children().size() - 1;
        for (int i = 0; i < bodyIndex; i++) {
            if (!quantifier.children().get(i).head().startsWith("DECL/")) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> declaredVariables(AlloyTerm quantifier) {
        Set<String> names = new HashSet<>();
        int bodyIndex = quantifier.children().size() - 1;
        for (int i = 0; i < bodyIndex; i++) {
            for (AlloyTerm child : quantifier.children().get(i).children()) {
                if (child.isVariable()) {
                    names.add(child.atom());
                }
            }
        }
        return names;
    }

    @LeanVerifiedRewrite("R0-CORE-017")
    private static void flatten(String head, AlloyTerm term, List<AlloyTerm> output) {
        if (head.equals(term.head())) {
            for (AlloyTerm child : term.children()) {
                flatten(head, child, output);
            }
        } else {
            output.add(term);
        }
    }

    private static AlloyTerm encodeArity(AlloyTerm term, ArityMode arityMode) {
        if (arityMode != ArityMode.FIXED || !ASSOCIATIVE.contains(term.head())
                || term.children().size() <= 2) {
            return term;
        }
        List<AlloyTerm> children = term.children();
        AlloyTerm suffix = children.get(children.size() - 1);
        for (int i = children.size() - 2; i >= 0; i--) {
            suffix = AlloyTerm.node(term.head(), children.get(i), suffix);
        }
        return suffix;
    }

    private static List<AlloyTerm> deduplicate(List<AlloyTerm> children) {
        List<AlloyTerm> output = new ArrayList<>(children.size());
        AlloyTerm previous = null;
        for (AlloyTerm child : children) {
            if (previous == null || !previous.equals(child)) {
                output.add(child);
                previous = child;
            }
        }
        return output;
    }

    private static boolean containsComplement(List<AlloyTerm> children) {
        Set<AlloyTerm> positive = new HashSet<>();
        Set<AlloyTerm> negative = new HashSet<>();
        Set<AlloyTerm> terms = new HashSet<>(children);
        for (AlloyTerm child : children) {
            if ("BOOL/NOT".equals(child.head()) && child.children().size() == 1) {
                AlloyTerm item = child.children().get(0);
                if (positive.contains(item)) {
                    return true;
                }
                negative.add(item);
            } else {
                if (negative.contains(child)) {
                    return true;
                }
                positive.add(child);
            }
            String dualHead = atomicNegationDual(child.head());
            if (dualHead != null
                    && terms.contains(AlloyTerm.of(dualHead, child.atom(), child.children()))) {
                return true;
            }
        }
        return false;
    }

    private static List<AlloyTerm> withoutConstant(List<AlloyTerm> children, String value) {
        List<AlloyTerm> output = new ArrayList<>(children.size());
        for (AlloyTerm child : children) {
            if (!isConstant(child, value)) {
                output.add(child);
            }
        }
        return output;
    }

    private static boolean containsConstant(List<AlloyTerm> children, String value) {
        for (AlloyTerm child : children) {
            if (isConstant(child, value)) {
                return true;
            }
        }
        return false;
    }

    private static AlloyTerm finishVariadic(String head, List<AlloyTerm> children, boolean identity) {
        if (children.isEmpty()) {
            return bool(identity);
        }
        if (children.size() == 1) {
            return children.get(0);
        }
        return AlloyTerm.node(head, children);
    }

    private static String canonicalHead(String head) {
        switch (head) {
            case "BF/AND":
            case "LF/AND":
                return "BOOL/AND";
            case "BF/OR":
            case "LF/OR":
                return "BOOL/OR";
            case "BF/IMPLIES":
                return "BOOL/IMPLIES";
            case "BF/IFF":
                return "BOOL/IFF";
            case "UF/NOT":
                return "BOOL/NOT";
            case "BE/PLUS":
                return "REL/PLUS";
            case "BE/INTERSECT":
                return "REL/INTERSECT";
            case "BE/MINUS":
                return "REL/MINUS";
            case "BE/JOIN":
                return "REL/JOIN";
            case "BE/ARROW":
                return "REL/ARROW";
            case "BE/MUL":
                return "ARITH/MUL";
            case "BE/IPLUS":
                return "ARITH/PLUS";
            case "LE/DISJOINT":
                return "LIST/DISJOINT";
            default:
                return head;
        }
    }

    private static AlloyTerm not(AlloyTerm child) {
        return AlloyTerm.node("BOOL/NOT", child);
    }

    private static AlloyTerm bool(boolean value) {
        return constant(Boolean.toString(value));
    }

    private static AlloyTerm constant(String value) {
        return AlloyTerm.atom("CONST", value);
    }

    private static boolean isConstant(AlloyTerm term, String value) {
        return "CONST".equals(term.head()) && value.equalsIgnoreCase(term.atom());
    }

    private static AlloyTerm newTerm(String head, String atom, List<AlloyTerm> children) {
        if (children.isEmpty()) {
            return AlloyTerm.atom(head, atom);
        }
        return AlloyTerm.node(head, children);
    }

    static final class Pass {
        final AlloyTerm term;
        final int applications;

        Pass(AlloyTerm term, int applications) {
            this.term = term;
            this.applications = applications;
        }
    }

    private static final class Counter {
        private int changes;
    }
}
