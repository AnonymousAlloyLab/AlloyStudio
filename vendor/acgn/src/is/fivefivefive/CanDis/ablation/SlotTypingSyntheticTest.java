package is.fivefivefive.CanDis.ablation;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import is.fivefivefive.CanDis.core.egraph.SlottedEGraph;
import parser.ast.nodes.ModelUnit;

/** Finite witnesses for the synthetic-only unbounded construction entry point. */
public final class SlotTypingSyntheticTest {
    public static void main(String[] args) throws Exception {
        var model = new ModelUnit(null, CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {} var sig Live in A {}\n"
                + "pred p { all x,y:A | x in y }\n"
                + "pred q { all x:A | once x in Live implies always x in Live }\n"
                + "pred r { some (A + A) and some A }\n"));
        int checks = 0;
        for (var predicate : model.getPredDeclList()) {
            for (boolean typed : new boolean[] {false, true}) {
                var term = AlloyAstTermAdapter.fromPredicate(predicate, typed);
                var representation = new SlottedEGraph(typed).representUnbounded(term);
                if (!representation.frontier().equals(SlottedEGraph.normalForm(term))) {
                    throw new AssertionError("Small bounded/unbounded frontier differs: " + predicate.getName());
                }
                if (representation.stats().eclasses <= 0 || representation.stats().enodes <= 0) {
                    throw new AssertionError("Missing representation graph");
                }
                checks += 2;
            }
        }
        var nine = new ModelUnit(null, CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {}\n"
                + "pred p { all a,b,c,d,e,f,g,h,i:A | a in i }\n"
                + "pred q { all a,b,c,d,e,f,g,h,i:A | i in a }\n"));
        var left = AlloyAstTermAdapter.fromPredicate(nine.getPredDeclList().stream()
                .filter(p -> p.getName().equals("p")).findFirst().orElseThrow(), true);
        var right = AlloyAstTermAdapter.fromPredicate(nine.getPredDeclList().stream()
                .filter(p -> p.getName().equals("q")).findFirst().orElseThrow(), true);
        if (SlottedEGraph.alphaRepresentative(left).equals(SlottedEGraph.alphaRepresentative(right))) {
            throw new AssertionError("Nine-binding witness no longer distinguishes the bounded prefix");
        }
        if (!new SlottedEGraph(true).representUnbounded(left).frontier().equals(
                new SlottedEGraph(true).representUnbounded(right).frontier())) {
            throw new AssertionError("Unbounded nine-binding alignment stopped at the 8! prefix");
        }
        checks += 2;
        System.out.println("SlotTypingSyntheticTest: " + checks + " checks passed");
    }
}
