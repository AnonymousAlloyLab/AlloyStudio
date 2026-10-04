package is.fivefivefive.CanDis.theory;

import is.fivefivefive.CanDis.core.AlloyOperatorPolicy;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** P2-02: exercise every finite operator-policy branch without inferring law authority. */
public final class PolicyRepresentationRegressionTest {
    private static int checks;

    public static void main(String[] args) {
        for (SemanticProfile profile : new SemanticProfile[] {
                SemanticProfile.alloyOverflowForbidding(), SemanticProfile.alloyModular()}) {
            for (Opcode opcode : Opcode.values()) {
                for (int arity : new int[] {0, 1, 2, 3, Integer.MAX_VALUE}) {
                    for (boolean variadic : new boolean[] {false, true}) {
                        AlloyOperatorPolicy policy = AlloyOperatorPolicy.forShape(opcode, arity, variadic, profile);
                        check(policy.arityPolicy() != null && policy.siblingQuotient() != null
                                && policy.flatLicense() != null && policy.unitLicense() != null);
                        check(policy.unitLicense() == UnitLicense.ABSENT);
                        if (opcode == Opcode.CALL) {
                            check(policy.arityPolicy().equals(ArityPolicy.exact(arity)));
                            check(policy.siblingQuotient() == SiblingQuotient.ORDERED_SEQUENCE);
                            check(!policy.flatLicense().enabled());
                        }
                    }
                }
            }
        }
        reject(() -> AlloyOperatorPolicy.forShape(null, 2, false, SemanticProfile.alloyOverflowForbidding()));
        reject(() -> AlloyOperatorPolicy.forShape(Opcode.CALL, 2, false, null));
        reject(() -> AlloyOperatorPolicy.forShape(Opcode.CALL, -1, false, SemanticProfile.alloyOverflowForbidding()));
        System.out.println("PolicyRepresentationRegressionTest passed: " + checks + " checks");
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Policy coordinate mismatch");
    }

    private static void reject(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | NullPointerException expected) {
            checks++;
            return;
        }
        throw new AssertionError("Invalid policy input accepted");
    }
}
