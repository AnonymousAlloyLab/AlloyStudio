package is.fivefivefive.CanDis.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Source-authenticated zero-cost prefix presentation, not a source rewrite. */
final class FastSamePhasePrefix {
    private FastSamePhasePrefix() { }

    static boolean matches(NormalForm left, NormalForm right,
            List<QuantiVar> a, List<QuantiVar> b) {
        if (!left.matrixDeclarationsMatchSource() || !right.matrixDeclarationsMatchSource()
                || !left.getPhasePath().equals(right.getPhasePath())
                || left.getTemporalOp() != right.getTemporalOp() || a.size() != b.size()) return false;
        var ai = positions(left, a);
        var bi = positions(right, b);
        if (ai == null || bi == null) return false;
        for (int i = 0; i < a.size(); i++) {
            var x = a.get(i);
            var y = b.get(i);
            var xt = x.getExactAlloyType();
            var yt = y.getExactAlloyType();
            if (x.getQuantifier() != y.getQuantifier() || x.getCardinality() != y.getCardinality()
                    || x.getDisjointnessClass() != y.getDisjointnessClass()
                    || xt == null || yt == null || !xt.hasParserAuthenticatedAncestry()
                    || !yt.hasParserAuthenticatedAncestry() || !xt.equals(yt)
                    || !xt.ancestryAlternatives().equals(yt.ancestryAlternatives())) return false;
            var xs = x.getDeclarationFrames();
            var ys = y.getDeclarationFrames();
            if (xs.isEmpty() || xs.size() != ys.size()) return false;
            boolean rigid = x.getQuantifier() != QuantiVar.Quantifier.ALL
                    && x.getQuantifier() != QuantiVar.Quantifier.SOME || x.getDisjointnessClass() != 0;
            for (int j = 0; j < xs.size(); j++) {
                var xf = xs.get(j);
                var yf = ys.get(j);
                if (!left.authenticDeclarationFrame(xf) || !right.authenticDeclarationFrame(yf)
                        || xf.variable() != x || yf.variable() != y
                        || !xf.domain().equals(yf.domain())
                        || !xf.dependencies().isEmpty() || !yf.dependencies().isEmpty()) return false;
                // Never turn a grouped ONE/LONE into nested quantifiers, or
                // merge independent disjoint groups into one constraint.
                if (rigid && (xf.tupleSlot() != yf.tupleSlot()
                        || !tuple(a, xf, ai).equals(tuple(b, yf, bi)))) return false;
            }
        }
        return true;
    }

    private static Map<QuantiVar, Integer> positions(NormalForm form, List<QuantiVar> order) {
        if (order.size() != form.getMatrixQuantiVars().size()) return null;
        var positions = new IdentityHashMap<QuantiVar, Integer>();
        var names = new HashSet<String>();
        for (int i = 0; i < order.size(); i++) {
            var q = order.get(i);
            if (!form.getMatrixQuantiVars().contains(q) || positions.put(q, i) != null
                    || !names.add(q.getName())) return null;
        }
        return positions;
    }

    private static List<Integer> tuple(List<QuantiVar> order, NormalForm.DeclarationFrame frame,
            Map<QuantiVar, Integer> positions) {
        var result = new ArrayList<Integer>();
        for (var q : order) for (var f : q.getDeclarationFrames())
            if (f.owner() == frame.owner() && f.declarationOccurrence() == frame.declarationOccurrence())
                result.add(positions.get(q));
        return result;
    }
}
