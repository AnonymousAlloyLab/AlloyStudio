package is.fivefivefive.CanDis;

import java.util.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** The cheap plan is the exact prefix component of the complete adapter. */
public final class PrefixBinderPlanTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        List<String> bodies = List.of(
                "all x,y:A | x in y.r", "some x,y:A | x in y.r",
                "all x:A | all y:A | x in y.r", "some x:A | some y:A | x in y.r",
                "all x:A | some y:A | x in y.r", "all x,y:S | x in y.r",
                "all x,y:A | one z:A | z in x.r and z in y.r",
                "all x,y:A | some z:A-x | z in y.r",
                "all disj x,y:A | x in y.r", "one x,y:A | x in y.r",
                "all x:A, y:x.r | x in y.r", "some A");
        for (String body : bodies) {
            var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,
                    "sig A {r:set A} sig S in A {} pred p {" + body + "}");
            var visitor = new MASGVisitor(new GlobalVariables(), Set.of("p"), module);
            visitor.visit(new ModelUnit(null, module), null);
            var prepared = Canonical.prepare(visitor.getForest().get(visitor.getForestId("p")));
            var forms = prepared.normalizedForms();
            var complete = TheoryAlloyAdapter.adapt(forms);
            for (int i=0; i<forms.size(); i++) {
                var prefix = TheoryAlloyAdapter.prefixBinderPlan(forms.get(i));
                var old = complete.phaseBinderDescriptors().get(i);
                check(prefix.descriptor().structuralKey().equals(old.structuralKey()),
                        "complete descriptor, slots and certified group: " + body);
                check(prefix.sourceToCoordinate().equals(complete.phaseSourceCoordinates().get(i)),
                        "source-to-canonical coordinate map: " + body);
                for (var proof : prefix.descriptor().automorphisms().generatorCertificates()) {
                    CertificateVerifier.verify(proof);
                    checks++;
                }
                try {
                    prefix.sourceToCoordinate().add(99);
                    throw new AssertionError("mutable prefix coordinates");
                } catch (UnsupportedOperationException expected) { checks++; }
            }
        }
        System.out.println("PrefixBinderPlanTest: " + checks + " checks passed");
    }
}
