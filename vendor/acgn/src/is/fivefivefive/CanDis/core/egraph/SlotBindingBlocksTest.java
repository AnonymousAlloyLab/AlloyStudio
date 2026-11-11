package is.fivefivefive.CanDis.core.egraph;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import parser.ast.nodes.ModelUnit;

public final class SlotBindingBlocksTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, """
                sig A { r: set B }
                sig B {}
                sig Sub in A {}
                pred p { all a,b:A, c,d:B | c in a.r and d in b.r }
                pred q { all d:B | all b:A | all c:B | all a:A | c in a.r and d in b.r }
                pred e { some a:A, b:B | b in a.r }
                pred f { some b:B | some a:A | b in a.r }
                pred single { all a:A, b:B | b in a.r }
                pred reversed { all b:B | all a:A | b in a.r }
                pred shadow { all x:A | all x:B | some x }
                pred renamed { all a:A, b:B | some b }
                pred temporal { all a:A | always (all b:B | b in a.r) }
                pred mixed { all a:A | some b:B | b in a.r }
                pred dependent { all a:A | all b:a.r | some b }
                pred disjoint { all disj a,b:A | all disj c,d:A | a != c }
                pred branches { (all a:A | some a) and (all b:B | some b) }
                pred domainLeft { all a:A, b:Sub | some a.r }
                pred domainRight { all a:A, b:Sub | some b.r }
                assert c1 { p iff q }
                assert c2 { e iff f }
                assert c3 { single iff reversed }
                assert c4 { shadow iff renamed }
                assert domainDifference { domainLeft iff domainRight }
                check c1 for 3
                check c2 for 3
                check c3 for 3
                check c4 for 3
                check domainDifference for 3
                """);
        var model = new ModelUnit(null, module);
        for (boolean typed : List.of(false, true)) {
            Map<String, AlloyTerm> terms = new TreeMap<>();
            for (var predicate : model.getPredDeclList()) {
                terms.put(predicate.getName(), AlloyAstTermAdapter.fromPredicate(predicate, typed));
            }
            for (String[] pair : new String[][] {{"p", "q"}, {"e", "f"}, {"single", "reversed"}, {"shadow", "renamed"}}) {
                var left = SlottedEGraph.alphaRepresentative(terms.get(pair[0]));
                var right = SlottedEGraph.alphaRepresentative(terms.get(pair[1]));
                check(left.equals(right), "block combination/alpha: " + pair[0] + " typed=" + typed);
                check(new SlottedEGraph(typed).compare(terms.get(pair[0]), terms.get(pair[1])).distance == 0,
                        "zero comparison: " + pair[0]);
                check(left.equals(SlottedEGraph.alphaRepresentative(left)), "block normalization idempotent");
            }
            for (String name : List.of("temporal", "mixed", "dependent", "disjoint", "branches")) {
                AlloyTerm before = terms.get(name), after = SlottedEGraph.alphaRepresentative(before);
                check(quantifiers(before) == quantifiers(after), "scope barrier retained: " + name);
            }
            check(quantifiers(SlottedEGraph.alphaRepresentative(terms.get("q"))) == 1,
                    "four nested universals become one block");
            long candidates = SlotPermutationSearch.forEach(SlottedEGraph.alphaRepresentative(terms.get("p")), false, ignored -> {});
            check(candidates == (typed ? 4 : 24), "mixed two-plus-two candidate count");
            check(new SlottedEGraph(typed).compare(terms.get("domainLeft"), terms.get("domainRight")).distance > 0,
                    "subset domains retained despite sharing a primitive slot type");
            System.out.println("source 2A+2B typed=" + typed + " candidates=" + candidates);
        }
        A4Options options = new A4Options();
        options.solver = kodkod.engine.satlab.SATFactory.DEFAULT;
        for (var command : module.getAllCommands()) {
            check(TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,
                    module.getAllReachableSigs(), command, options).satisfiable()
                    == command.label.equals("domainDifference"),
                    "bounded source equivalence: " + command.label);
        }
        System.out.println("SlotBindingBlocksTest: " + checks + " checks passed");
    }

    private static int quantifiers(AlloyTerm term) {
        int n = term.head().startsWith("QF/") ? 1 : 0;
        for (AlloyTerm child : term.children()) n += quantifiers(child);
        return n;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
