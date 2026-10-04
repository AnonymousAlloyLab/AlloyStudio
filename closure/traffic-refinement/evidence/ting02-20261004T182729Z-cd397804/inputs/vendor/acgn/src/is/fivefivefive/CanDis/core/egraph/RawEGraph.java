package is.fivefivefive.CanDis.core.egraph;

import java.util.LinkedHashSet;
import java.util.Set;

/** Conventional fixed-arity e-graph saturated with the shared Alloy rules. */
public final class RawEGraph implements AblationEngine {
    private static final int MAX_TERM_SIZE = 50_000;
    private final boolean deBruijnVariables;

    public RawEGraph() {
        this(false);
    }

    RawEGraph(boolean deBruijnVariables) {
        this.deBruijnVariables = deBruijnVariables;
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
                    leftFrontier, AlloyRewriteSystem.ArityMode.FIXED);
            AlloyRewriteSystem.Pass rightPass = AlloyRewriteSystem.rewriteOnce(
                    rightFrontier, AlloyRewriteSystem.ArityMode.FIXED);
            int roundApplications = leftPass.applications + rightPass.applications;
            if (roundApplications == 0) {
                break;
            }
            iterations++;
            applications += roundApplications;
            if (leftPass.applications > 0 && leftPass.term.size() <= MAX_TERM_SIZE) {
                leftFrontier = leftPass.term;
                AlloyTerm stored = store(leftFrontier);
                graph.union(leftRoot, graph.add(stored));
                leftRoots.add(stored);
            }
            if (rightPass.applications > 0 && rightPass.term.size() <= MAX_TERM_SIZE) {
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
