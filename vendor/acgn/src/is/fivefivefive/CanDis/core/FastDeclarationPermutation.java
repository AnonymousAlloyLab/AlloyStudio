package is.fivefivefive.CanDis.core;

import java.util.*;
import java.util.function.BiPredicate;

/** Replay-only exchange within an existing source-observed grouped ALL/SOME declaration.
 * No new alpha candidates, cross-block moves, temporal moves or source rewrites. */
final class FastDeclarationPermutation {
    private FastDeclarationPermutation() { }

    static BiPredicate<Integer,Integer> admission(NormalForm form,List<QuantiVar> ordered) {
        boolean supported=FastPaidDeclarationAlignment.supports(List.of(form))
                && form.getLocalQuantiVars().isEmpty();
        Set<QuantiVar> owned=Collections.newSetFromMap(new IdentityHashMap<>());
        owned.addAll(form.getMatrixQuantiVars());
        return (i,j) -> {
            QuantiVar a=ordered.get(i),b=ordered.get(j);
            return supported && owned.contains(a) && owned.contains(b)
                    && a.getDisjointnessClass()==0 && b.getDisjointnessClass()==0
                    && (a.getQuantifier()==QuantiVar.Quantifier.ALL || a.getQuantifier()==QuantiVar.Quantifier.SOME)
                    && a.getQuantifier()==b.getQuantifier()
                    && a.getBindingPath().equals(b.getBindingPath())
                    && a.getCarrierTypeName().equals(b.getCarrierTypeName())
                    && a.getExactAlloyType()!=null
                    && a.getExactAlloyType().sameOccurrenceEvidenceAs(b.getExactAlloyType());
        };
    }
}
