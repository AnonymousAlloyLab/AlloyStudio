package is.fivefivefive.CanDis.core.egraph;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;

/** Bounded natural/synthetic gate for the shared slotted operand orientation. */
public final class SlotRewriteOrderTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        checkPair(root.resolve("classified-data/socialMedia/under/x9CXb8pLEAhJgTSFk_inv5.als"),
                "inv5", "inv5c", false);
        String synthetic = "synthetic-data/slot-binding-stress/models/";
        checkPair(root.resolve(synthetic + "heterogeneous_alpha/CORRECT/generated_stress0010.als"),
                "stress0010", "stress0010C", true);
        checkPair(root.resolve(synthetic + "boolean_aci/CORRECT/generated_stress0050.als"),
                "stress0050", "stress0050C", true);
        checkPair(root.resolve(synthetic + "negative_cross_type/NEGATIVE/generated_stress0210.als"),
                "stress0210", "stress0210C", false);
        checkSmallSemanticControls();
        System.out.println("SlotRewriteOrderTest: " + checks + " checks passed");
    }

    private static void checkPair(Path path, String leftName, String rightName, boolean equivalent) throws Exception {
        var module = CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, path.toString());
        List<Predicate> predicates = new ArrayList<>();
        collect(new ModelUnit(null, module), predicates);
        for (boolean typed : List.of(false, true)) {
            var left = AlloyAstTermAdapter.fromPredicate(find(predicates, leftName), typed);
            var right = AlloyAstTermAdapter.fromPredicate(find(predicates, rightName), typed);
            int leftRounds = stableRounds(left), rightRounds = stableRounds(right);
            var result = new SlottedEGraph(typed).compare(left, right);
            check(result.equivalent == equivalent, "expected relation: " + path + " typed=" + typed);
            check(result.stats.iterations < 32, "no exhausted rewrite pass budget: " + path);
            System.out.println(path.getFileName() + " typed=" + typed + " rounds="
                    + leftRounds + "/" + rightRounds + " distance=" + result.distance);
        }
    }

    private static int stableRounds(AlloyTerm input) {
        AlloyTerm current = SlotCanonicalizer.canonicalize(input);
        for (int round = 0; round < 8; round++) {
            var pass = AlloyRewriteSystem.rewriteSlottedOnce(current);
            if (pass.applications == 0) {
                List<AlloyTerm> operands = new ArrayList<>(current.children());
                SlotCanonicalizer.sortOperands(operands);
                var once = List.copyOf(operands);
                SlotCanonicalizer.sortOperands(operands);
                check(once.equals(operands), "shared ordering is idempotent");
                return round;
            }
            AlloyTerm next = SlotCanonicalizer.canonicalize(pass.term);
            check(!next.equals(current), "rewrite/alpha passes must not undo one another");
            current = next;
        }
        throw new AssertionError("Small smoke witness did not stabilize within eight rounds");
    }

    private static void checkSmallSemanticControls() throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, """
                sig User { r: set User }
                sig Influencer extends User {}
                pred p { all u:User, i:Influencer | i in u.r or i = u }
                pred q { all u:User, i:Influencer | i in u.r or u = i }
                pred same { all x,y:User | x in y.r }
                pred swapped { all x,y:User | y in x.r }
                pred bad { all u:User, i:Influencer | u in i.r or i = u }
                assert commutes { p iff q }
                assert sameDomain { same iff swapped }
                assert crossType { p iff bad }
                check commutes for 3
                check sameDomain for 3
                check crossType for 3
                """);
        A4Options options = new A4Options();
        options.solver = kodkod.engine.satlab.SATFactory.DEFAULT;
        int index = 0;
        for (var command : module.getAllCommands()) {
            var solution = TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,
                    module.getAllReachableSigs(), command, options);
            check(solution.satisfiable() == (index++ == 2), "Alloy semantic control: " + command.label);
        }
        List<Predicate> predicates = new ArrayList<>();
        collect(new ModelUnit(null, module), predicates);
        for (String[] names : new String[][] {{"p", "q"}, {"same", "swapped"}, {"p", "bad"}}) {
            var left = AlloyAstTermAdapter.fromPredicate(find(predicates, names[0]), true);
            var right = AlloyAstTermAdapter.fromPredicate(find(predicates, names[1]), true);
            check(new SlottedEGraph(true).compare(left, right).equivalent == !names[1].equals("bad"),
                    "typed engine agrees with semantic control");
            stableRounds(left);
            stableRounds(right);
        }
    }

    private static Predicate find(List<Predicate> predicates, String name) {
        return predicates.stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private static void collect(Node node, List<Predicate> predicates) {
        if (node instanceof Predicate predicate) { predicates.add(predicate); return; }
        if (node.getChildren() != null) for (Node child : node.getChildren()) if (child != null) collect(child, predicates);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
