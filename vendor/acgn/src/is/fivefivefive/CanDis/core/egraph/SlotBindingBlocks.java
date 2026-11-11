package is.fivefivefive.CanDis.core.egraph;

import java.util.ArrayList;
import java.util.List;

/** Combines independent scalar binders after source variables have been scoped. */
final class SlotBindingBlocks {
    private SlotBindingBlocks() {}

    static AlloyTerm normalize(AlloyTerm scoped) {
        List<AlloyTerm> children = new ArrayList<>();
        for (AlloyTerm child : scoped.children()) children.add(normalize(child));
        AlloyTerm term = scoped.withChildren(children);
        if (!isExchangeBlock(term)) return term;
        List<AlloyTerm> declarations = new ArrayList<>();
        AlloyTerm current = term;
        AlloyTerm body;
        while (true) {
            for (int i = 0; i < current.children().size() - 1; i++) {
                AlloyTerm declaration = current.children().get(i);
                AlloyTerm domain = canonicalDomain(declaration.children().get(declaration.children().size() - 1));
                for (int j = 0; j < declaration.children().size() - 1; j++) {
                    declarations.add(declaration.withChildren(List.of(declaration.children().get(j), domain)));
                }
            }
            body = current.children().get(current.children().size() - 1);
            AlloyTerm nested = body;
            while (nested.head().equals("Body") && nested.children().size() == 1) {
                nested = nested.children().get(0);
            }
            if (!nested.head().equals(term.head()) || !isExchangeBlock(nested)) break;
            current = nested;
        }
        declarations.add(body);
        return term.withChildren(declarations);
    }

    static boolean isExchangeBlock(AlloyTerm term) {
        if (!(term.head().equals("QF/ALL") || term.head().equals("QF/SOME"))
                || term.children().size() < 2) return false;
        int last = term.children().size() - 1;
        if (term.children().get(last).head().startsWith("DECL/")) return false;
        for (int i = 0; i < last; i++) {
            AlloyTerm declaration = term.children().get(i);
            if (!declaration.head().equals("DECL/VarDecl/disj=false/var=false")
                    || declaration.children().size() < 2) return false;
            int domainIndex = declaration.children().size() - 1;
            for (int j = 0; j < domainIndex; j++) {
                if (!declaration.children().get(j).isVariable()) return false;
            }
            AlloyTerm domain = canonicalDomain(declaration.children().get(domainIndex));
            if (!(domain.head().equals("UE/ONE") && domain.children().size() == 1
                    && domain.children().get(0).head().equals("SIG"))) return false;
        }
        return true;
    }

    private static AlloyTerm canonicalDomain(AlloyTerm domain) {
        while (domain.head().equals("UE/NOOP") && domain.children().size() == 1) {
            domain = domain.children().get(0);
        }
        if (domain.head().equals("UE/ONE") && domain.children().size() == 1) {
            AlloyTerm signature = domain.children().get(0);
            while (signature.head().equals("UE/NOOP") && signature.children().size() == 1) {
                signature = signature.children().get(0);
            }
            return domain.withChildren(List.of(signature));
        }
        return domain;
    }
}
