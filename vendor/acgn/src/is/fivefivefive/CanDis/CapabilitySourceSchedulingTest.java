package is.fivefivefive.CanDis;

import java.util.List;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;

/** Source normalization must commute with supported prenex/negation routes. */
public final class CapabilitySourceSchedulingTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void tamper(NativeRepairTrace trace) {
        var changed = trace.json().put("distance", trace.distance() + 1);
        try {
            trace.replay(changed);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            checks++;
            return;
        }
        throw new AssertionError("tampered selected distance accepted");
    }

    private static void pair(String left, String right, boolean equivalent) throws Exception {
        String source = "sig A { r: set A } sig B {} pred opaque { some B } "
                + "pred p {" + left + "} pred q {" + right + "}";
        sourcePair(source, equivalent);
    }

    private static void sourcePair(String source, boolean equivalent) throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        var options = new A4Options();
        options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        var command = new Command(false, 3, 4, 4, null,
                CompUtil.parseOneExpression_fromString(module, "not (p iff q)"));
        check(TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,
                module.getAllReachableSigs(), command, options).satisfiable() != equivalent,
                "independent bounded source check: " + source);
        nativePair(source, equivalent);
    }

    private static void nativePair(String source, boolean equivalent) throws Exception {
        for (boolean renamed : List.of(false, true)) {
            String text = renamed ? source.replace("A", "Carrier").replace("B", "Other") : source;
            var a = UnusedBindingDescopeTest.prepare(text, "p");
            var b = UnusedBindingDescopeTest.prepare(text, "q");
            var ca = CanonicalAlloyPipeline.prepare(a);
            var cb = CanonicalAlloyPipeline.prepare(b);
            for (boolean reverse : List.of(false, true)) {
                var l = reverse ? b : a;
                var r = reverse ? a : b;
                var fast = CanonicalRepairTraces.fast(l, r);
                var cert = CanonicalRepairTraces.certified(reverse ? cb : ca, reverse ? ca : cb);
                check((fast.replay(fast.json()) == 0) == equivalent, "Fast source law: " + source);
                check((cert.replay(cert.json()) == 0) == equivalent, "certified source law: " + source);
                if (equivalent) {
                    check(NativeOperationComparison.compare(l, r, fast, cert).status()
                            == NativeOperationComparison.Status.MATCHED, "native source correspondence: " + source);
                }
                tamper(fast);
                tamper(cert);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        interfaceParameters();
        membershipScheduling();
        pair("(some x:A | x->x in r) and (opaque and no A and some A)",
                "some x:A | x->x in r and opaque and no A and some A", true);
        pair("(some x,y:A | x->y in r) and (opaque and no A and some A)",
                "some y,x:A | x->y in r and opaque and no A and some A", true);
        pair("not ((some x,y:A | x->y in r) and (opaque and no A and some A))",
                "all y,x:A | not (x->y in r) or not (opaque and no A and some A)", true);
        pair("no x:A | x->x in r and opaque and (A in A+B or no A) and some A",
                "all x:A | not (x->x in r and opaque and (A in A+B or no A) and some A)", true);
        pair("not (all x:A | x->x in r and opaque and ((some r and A in A+B) or some A))",
                "some x:A | not (x->x in r and opaque and ((some r and A in A+B) or some A))", true);
        pair("not ((some x,y:A | x->y in r) and ((opaque and r in A->A and some B and some A)"
                        + " and ((no B or A in A+B) and no A)))",
                "all y,x:A | not (x->y in r) or not ((no B or A in A+B) and no A)"
                        + " or not (opaque and r in A->A and some B and some A)", true);
        pair("(some x:A | x->x in r) and opaque", "some x:A | x->x in r and opaque", true);
        pair("all x:A | some B", "some B", false);
        pair("some x:A | no none", "no none", false);
        pair("some disj x,y:A | no none", "some A", false);
        pair("one x:A | no none", "some A", false);
        pair("A in B", "A in A+B", false);
        pair("lone x:A,y:none | no none", "no none", true);
        pair("not (one x:A,y:none | no none)", "no none", true);
        // Negating this higher-order assertion is not supported by Alloy's SAT
        // translator. The unique empty binding is proved in the Lean contract.
        nativePair("sig A {} pred p {some x:set none | no none} pred q {no none}", true);
        for (String family : List.of("alpha_ac", "alpha_binder_permutation",
                "binder_permutation_prenex", "ac_logical", "mixed")) {
            check(CapabilityBenchmark.isComposedFamily(family), "composed family " + family);
        }
        for (String family : List.of("alpha", "aci", "binder_permutation",
                "safe_prenex", "logical_normalization", "temporal_dual")) {
            check(!CapabilityBenchmark.isComposedFamily(family), "simple family " + family);
        }
        System.out.println("CapabilitySourceSchedulingTest: " + checks + " checks passed");
    }

    private static void membershipScheduling() throws Exception {
        String scope = "sig A { r: set A, s: set A } sig B, C in A {} ";
        for (String[] law : List.of(
                new String[]{"all x:A | x not in B&C", "no B&C"},
                new String[]{"all x:A | x in B&C", "A in B&C"},
                new String[]{"no x:A | x not in B&C", "A in B&C"},
                new String[]{"some x:A | x in B&C", "some B&C"},
                new String[]{"some x:A | x not in B&C", "some A-(B&C)"},
                new String[]{"all x,y:A | x in B", "A in B"},
                new String[]{"(all x:A | x.r in x.s) and (all y:A | y.r<:B in B)",
                        "all x:A | x.r in x.s"})) {
            sourcePair(scope + "pred p {" + law[0] + "} pred q {" + law[1] + "}", true);
        }
        for (String left : List.of("all disj x,y:A | x in B",
                "all x:A,y:x.r | y in B", "all x:A | x in x.r")) {
            sourcePair(scope + "pred p {" + left + "} pred q {A in B}", false);
        }
        sourcePair(scope + "pred p {(all x:A | x.r in x.s) and (some y:A | y.r<:B in B)}"
                + "pred q {all x:A | x.r in x.s}", false);
        for (String multiplicity : List.of("set", "lone")) {
            nativePair(scope + "pred p {all x:" + multiplicity
                    + " A | x not in B&C} pred q {some none}", true);
        }
    }

    private static void interfaceParameters() throws Exception {
        String source = "sig S {} sig T {} pred p[x:S] {some x} "
                + "pred q[x:T] {some x} pred r[x:T] {no x}";
        var left = UnusedBindingDescopeTest.prepare(source, "p");
        for (String name : List.of("q", "r")) {
            var right = UnusedBindingDescopeTest.prepare(source, name);
            int expected = name.equals("q") ? 1 : 2;
            var a = CanonicalAlloyPipeline.prepare(left);
            var b = CanonicalAlloyPipeline.prepare(right);
            check(!a.equivalentTo(b), "parameter carrier remains observable");
            check(Canonical.distance(left, right) == expected, "Fast interface edits");
            check(Canonical.distance(right, left) == expected, "Fast reverse interface edits");
            check(CanonicalAlloyPipeline.distance(a, b) == expected, "certified interface edits");
            check(CanonicalAlloyPipeline.distance(b, a) == expected, "certified reverse interface edits");
        }
    }
}
