package is.fivefivefive.CanDis.core.egraph;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Java replica of egglog's core execution model: constructor/function-table
 * hash-consing, union facts, semi-naive rule rounds, and congruence rebuilding.
 * It intentionally implements the core needed by this Alloy ablation rather
 * than egglog's textual language front end.
 */
public final class JavaEgglog implements AblationEngine {
    private static final int MAX_TERM_SIZE = 50_000;
    private final boolean deBruijnVariables;

    public JavaEgglog() {
        this(false);
    }

    JavaEgglog(boolean deBruijnVariables) {
        this.deBruijnVariables = deBruijnVariables;
    }

    public static String ruleSetVersion() {
        return AlloyRewriteSystem.RULE_SET_VERSION;
    }

    public static List<String> ruleNames() {
        return AlloyRewriteSystem.ruleNames();
    }

    /** Returns the fixed-point representative used by the Alloy rewrite program. */
    public static AlloyTerm normalForm(AlloyTerm term) {
        AlloyTerm current = term;
        for (int iteration = 0; iteration < AlloyRewriteSystem.MAX_ITERATIONS; iteration++) {
            AlloyRewriteSystem.Pass pass = AlloyRewriteSystem.rewriteOnce(
                    current, AlloyRewriteSystem.ArityMode.VARIADIC);
            if (pass.applications == 0 || pass.term.size() > MAX_TERM_SIZE) {
                return current;
            }
            current = pass.term;
        }
        return current;
    }

    @Override
    public Result compare(AlloyTerm left, AlloyTerm right) {
        IntEGraph graph = new IntEGraph();
        AlloyTerm storedLeft = store(left);
        AlloyTerm storedRight = store(right);
        int leftRoot = graph.add(storedLeft);
        int rightRoot = graph.add(storedRight);
        AlloyTerm leftFrontier = left;
        AlloyTerm rightFrontier = right;
        Set<AlloyTerm> leftRoots = new LinkedHashSet<>();
        Set<AlloyTerm> rightRoots = new LinkedHashSet<>();
        leftRoots.add(storedLeft);
        rightRoots.add(storedRight);
        long applications = 0;
        long iterations = 0;

        for (int iteration = 0; iteration < AlloyRewriteSystem.MAX_ITERATIONS; iteration++) {
            AlloyRewriteSystem.Pass leftPass = AlloyRewriteSystem.rewriteOnce(
                    leftFrontier, AlloyRewriteSystem.ArityMode.VARIADIC);
            AlloyRewriteSystem.Pass rightPass = AlloyRewriteSystem.rewriteOnce(
                    rightFrontier, AlloyRewriteSystem.ArityMode.VARIADIC);
            int roundApplications = leftPass.applications + rightPass.applications;
            if (roundApplications == 0) {
                break;
            }
            iterations++;
            applications += roundApplications;
            if (leftPass.term.size() <= MAX_TERM_SIZE && leftPass.applications > 0) {
                leftFrontier = leftPass.term;
                AlloyTerm stored = store(leftFrontier);
                graph.union(leftRoot, graph.add(stored));
                leftRoots.add(stored);
            }
            if (rightPass.term.size() <= MAX_TERM_SIZE && rightPass.applications > 0) {
                rightFrontier = rightPass.term;
                AlloyTerm stored = store(rightFrontier);
                graph.union(rightRoot, graph.add(stored));
                rightRoots.add(stored);
            }
            graph.rebuild();
        }

        boolean sameClass = graph.find(leftRoot) == graph.find(rightRoot);
        int distance = sameClass ? 0 : EGraphEditDistance.minimum(leftRoots, rightRoots);
        return new Result(distance, graph.stats(applications, iterations));
    }

    private AlloyTerm store(AlloyTerm term) {
        return deBruijnVariables ? DeBruijnVariables.encode(term) : term;
    }
}
