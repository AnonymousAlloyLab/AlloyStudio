package is.fivefivefive.CanDis.core.egraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Converts bound Alloy variables to nameless De Bruijn indices. */
public final class DeBruijnVariables {
    private static final String BINDER = "@db:binder";
    private static final String INDEX_PREFIX = "@db:";

    private DeBruijnVariables() {
    }

    /**
     * Encodes every bound occurrence relative to its nearest enclosing binder.
     * Free variables retain their names. Declaration leaves become anonymous
     * binder markers, with their number and order retained by the term shape.
     */
    public static AlloyTerm encode(AlloyTerm term) {
        return encode(term, Collections.emptyList());
    }

    private static AlloyTerm encode(AlloyTerm term, List<String> scope) {
        if (term.isVariable()) {
            int index = indexOf(scope, term.atom());
            return index < 0 ? term : AlloyTerm.atom("VAR", INDEX_PREFIX + index);
        }
        if (isScopedBinder(term)) {
            return encodeScopedBinder(term, scope);
        }
        if ("LetExpr".equals(term.head()) && term.children().size() >= 3) {
            return encodeLet(term, scope);
        }
        return encodeChildren(term, scope);
    }

    private static AlloyTerm encodeScopedBinder(AlloyTerm term, List<String> outerScope) {
        List<String> scope = new ArrayList<>(outerScope);
        List<AlloyTerm> children = new ArrayList<>(term.children().size());
        for (AlloyTerm child : term.children()) {
            if (isDeclaration(child)) {
                EncodedDeclaration declaration = encodeDeclaration(child, scope);
                children.add(declaration.term);
                scope.addAll(declaration.boundNames);
            } else {
                children.add(encode(child, scope));
            }
        }
        return term.withChildren(children);
    }

    private static EncodedDeclaration encodeDeclaration(AlloyTerm term, List<String> scope) {
        List<AlloyTerm> original = term.children();
        List<AlloyTerm> children = new ArrayList<>(original.size());
        List<String> boundNames = new ArrayList<>();
        int variableCount = 0;
        while (variableCount < original.size() && original.get(variableCount).isVariable()) {
            boundNames.add(original.get(variableCount).atom());
            children.add(AlloyTerm.atom("VAR", BINDER));
            variableCount++;
        }
        for (int i = variableCount; i < original.size(); i++) {
            children.add(encode(original.get(i), scope));
        }
        return new EncodedDeclaration(term.withChildren(children), boundNames);
    }

    private static AlloyTerm encodeLet(AlloyTerm term, List<String> scope) {
        AlloyTerm variable = term.children().get(0);
        if (!variable.isVariable()) {
            return encodeChildren(term, scope);
        }
        List<AlloyTerm> children = new ArrayList<>(term.children().size());
        children.add(AlloyTerm.atom("VAR", BINDER));
        children.add(encode(term.children().get(1), scope));
        List<String> bodyScope = new ArrayList<>(scope);
        bodyScope.add(variable.atom());
        for (int i = 2; i < term.children().size(); i++) {
            children.add(encode(term.children().get(i), bodyScope));
        }
        return term.withChildren(children);
    }

    private static AlloyTerm encodeChildren(AlloyTerm term, List<String> scope) {
        if (term.children().isEmpty()) {
            return term;
        }
        List<AlloyTerm> children = new ArrayList<>(term.children().size());
        for (AlloyTerm child : term.children()) {
            children.add(encode(child, scope));
        }
        return term.withChildren(children);
    }

    private static int indexOf(List<String> scope, String name) {
        int index = 0;
        for (int i = scope.size() - 1; i >= 0; i--, index++) {
            if (scope.get(i).equals(name)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isScopedBinder(AlloyTerm term) {
        return "PREDICATE".equals(term.head())
                || term.head().startsWith("QF/")
                || term.head().startsWith("QE/");
    }

    private static boolean isDeclaration(AlloyTerm term) {
        return term.head().startsWith("DECL/");
    }

    private static final class EncodedDeclaration {
        private final AlloyTerm term;
        private final List<String> boundNames;

        private EncodedDeclaration(AlloyTerm term, List<String> boundNames) {
            this.term = term;
            this.boundNames = boundNames;
        }
    }
}
