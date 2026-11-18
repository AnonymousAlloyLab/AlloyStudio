package is.fivefivefive.CanDis;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.QuantiVar;
import parser.ast.nodes.ModelUnit;

/** Dependency transport is checked on issued frames, not domain-key text. */
public final class DependencyOrbitTest {
    private static int checks;
    private static List<QuantiVar> bindings(String declarations) throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig User { follows: set User } pred p { no follows & iden all " + declarations
                + " | (c != a and c not in a.follows and b in c.follows) iff a in c.follows }");
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of("p"), module);
        visitor.visit(new ModelUnit(null, module), null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId("p")))
                .normalizedForms().get(0).getLocalQuantiVars();
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Method guard = Class.forName("is.fivefivefive.CanDis.core.FastBinderOrbit")
                .getDeclaredMethod("preservesDependencies", List.class, int[].class);
        guard.setAccessible(true);
        var chain = bindings("a:User, b:a.follows, c:b.follows");
        check(chain.size() == 3, "complete chain census: " + chain);
        check(chain.get(1).getDeclarationFrames().get(0).domain()
                .equals(chain.get(2).getDeclarationFrames().get(0).domain()), "old relative keys collide");
        check(!(boolean) guard.invoke(null, chain, new int[]{0,2,1}), "different dependency owners cannot swap");
        check((boolean) guard.invoke(null, chain, new int[]{0,1,2}), "dependent identity is supported");
        var shared = bindings("a:User, b:a.follows, c:a.follows");
        check((boolean) guard.invoke(null, shared, new int[]{0,2,1}), "shared dependency does not prohibit exchange");
        var readByFixed = bindings("a,b:User, c:a.follows");
        check(!(boolean) guard.invoke(null, readByFixed, new int[]{1,0,2}), "unchanged slot reads transported owner");
        var independent = bindings("a,b,c:User");
        check((boolean) guard.invoke(null, independent, new int[]{2,0,1}), "independent three-cycle retained");
        check((boolean) guard.invoke(null, independent, new int[]{1,2,0}), "inverse three-cycle retained");
        System.out.println("DependencyOrbitTest: " + checks + " checks passed");
    }
}
