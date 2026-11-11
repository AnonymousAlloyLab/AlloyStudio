package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

/** Source-backed correspondence for IncidentLaws.lean's typed-head costs. */
public final class FastResultTypeCostTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        checkPair("typed-empty-join", "sig A {r:set B} sig B {s:C->D} sig C{} sig D{}",
                "some A.r", "some A.s", 2);
        checkPair("changed-result-type", "sig A {r:set B, s:set C} sig B{} sig C{}",
                "some A.r", "some A.s", 2);
        checkPair("same-result-type", "sig A {r,s:set B} sig B{}",
                "some A.r", "some A.s", 1);
        checkPair("same-spelled-field-owner", "sig P{} sig A {r:set P} sig B {r:set P}",
                "all p:P | #((A <: r).p) = 1", "all p:P | #((B <: r).p) = 1", 2);
        checkPair("alpha-same-type", "sig A {r:set A}",
                "all a:A | some a.r", "all b:A | some b.r", 0);
        checkPair("declaration-type-change", "sig A{} sig B{}",
                "all a:A | some a", "all b:B | some b", 1);
        checkPair("integer-result", "sig A {r,s:set B} sig B{}",
                "#(A.r) = 2", "#(A.s) = 2", 1);
        System.out.println("FastResultTypeCostTest: " + checks + " checks passed");
    }

    private static void checkPair(String name, String declarations, String left, String right,
            int expected) throws Exception {
        Path source = Files.createTempFile("fast-result-type-", ".als");
        try {
            Files.writeString(source, declarations + "\npred lhs {" + left + "}\npred rhs {" + right + "}\n");
            var module = AlloyUtil.compileAlloyModule(source.toString());
            var visitor = new MASGVisitor(new GlobalVariables(), Set.of("lhs", "rhs"), module);
            visitor.visit(new ModelUnit(null, module), null);
            var a = Canonical.prepare(visitor.getForest().get(visitor.getForestId("lhs")));
            var b = Canonical.prepare(visitor.getForest().get(visitor.getForestId("rhs")));
            var retainedA = Canonical.certificationIrTemporalFol(a);
            var retainedB = Canonical.certificationIrTemporalFol(b);
            var ca = CanonicalAlloyPipeline.prepare(a);
            var cb = CanonicalAlloyPipeline.prepare(b);
            check(Canonical.distance(a, b) == expected, name + " Fast expected " + expected
                    + " got " + Canonical.distance(a, b));
            check(Canonical.distance(b, a) == expected, name + " symmetric Fast cost");
            check(CanonicalAlloyPipeline.distance(ca, cb) == expected, name + " certified cost");
            check(retainedA.equals(Canonical.certificationIrTemporalFol(a))
                    && retainedB.equals(Canonical.certificationIrTemporalFol(b)), name + " retained sources");
            check(expected == 0 || !Canonical.edits(a, b).equals(java.util.List.of("no-op")),
                    name + " diagnostic is nonempty");
            if (name.equals("changed-result-type")) {
                check(Canonical.edits(a, b).stream().anyMatch(e -> e.contains("result type")),
                        "derived JOIN result type appears in diagnostic edits");
            }
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
