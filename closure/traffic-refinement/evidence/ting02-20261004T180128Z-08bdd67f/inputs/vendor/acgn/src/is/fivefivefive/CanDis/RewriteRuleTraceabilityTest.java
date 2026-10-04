package is.fivefivefive.CanDis;

import java.nio.file.Path;

/** Repository gate for the immutable R0 Lean/Java rewrite correspondence. */
public final class RewriteRuleTraceabilityTest {
    private RewriteRuleTraceabilityTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0])
                .toAbsolutePath().normalize();
        RewriteRuleTraceability.Assessment assessment =
                RewriteRuleTraceability.assess(root);
        if (!assessment.failures().isEmpty()) {
            throw new AssertionError(assessment.render());
        }
        if (assessment.rules() != 61) {
            throw new AssertionError("Expected 61 governed rule families, found "
                    + assessment.rules());
        }
        if (assessment.baselineRules() != 24) {
            throw new AssertionError("Expected 24 bootstrap rules, found "
                    + assessment.baselineRules());
        }
        System.out.println("RewriteRuleTraceabilityTest passed: 61 rule families, "
                + "24 bootstrap rules, 0 correspondence failures");
    }
}
