package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.util.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.MatrixDecision;

/** Capture must retain the selected witness even without an exported map. */
final class MatrixCaptureTest {
    private static int checks;
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static Constructor<?> constructor(String name) throws ReflectiveOperationException {
        var constructor = Class.forName(CanonicalDistance.class.getName() + "$" + name)
                .getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor;
    }

    static void run(Canonical.Prepared left, Canonical.Prepared right) throws Exception {
        var captureConstructor = constructor("DecisionCapture");
        var holderConstructor = constructor("BestMapping");
        var metadata = Class.forName(CanonicalDistance.class.getName() + "$EGraphMetadata");
        var distance = CanonicalDistance.class.getDeclaredMethod("matrixDistance", NormalForm.class,
                NormalForm.class, Map.class, Set.class, Set.class, metadata,
                metadata, holderConstructor.getDeclaringClass());
        distance.setAccessible(true);
        var decisionField = CanonicalDistance.class.getDeclaredField("DECISIONS");
        decisionField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var decisions = (ThreadLocal<Object>) decisionField.get(null);
        check(decisions.get() == null, "no enclosing capture");
        for (int direction = 0; direction < 2; direction++) {
            var a = direction == 0 ? left : right;
            var b = direction == 0 ? right : left;
            for (int phase = 0; phase < Math.min(a.normalizedForms().size(), b.normalizedForms().size()); phase++) {
                Integer numeric = null;
                Object selectedMap = null;
                for (boolean capture : List.of(false, true)) {
                    for (boolean export : List.of(false, true)) {
                        Object observation = capture ? captureConstructor.newInstance() : null;
                        Object holder = export ? holderConstructor.newInstance() : null;
                        if (capture) decisions.set(observation);
                        try {
                            int cost = (int) distance.invoke(null, a.normalizedForms().get(phase),
                                    b.normalizedForms().get(phase), Map.of(), Set.of(), Set.of(),
                                    null, null, holder);
                            if (numeric == null) numeric = cost;
                            check(cost == numeric, "capture/export must not change matrix cost");
                            if (export) {
                                Object map = field(holder, "mapping");
                                if (selectedMap == null) selectedMap = map;
                                check(map.equals(selectedMap), "export retains actual selected mapping");
                                check((field(holder, "decision") != null) == capture,
                                        "numeric-only export does not allocate decisions");
                            }
                            if (capture) {
                                Object selected = field(observation, "lastPhase");
                                check(field(selected, "mapping").equals(selectedMap),
                                        "capture without export retains selected mapping");
                                var matrix = (MatrixDecision<?>) field(selected, "matrix");
                                check(matrix != null && matrix.cost() == cost,
                                        "capture retains actual attaining decision, not a default holder");
                                if (export) check(matrix == field(holder, "decision"),
                                        "caller-supplied holder and captured phase share the actual decision");
                            }
                        } finally { decisions.remove(); }
                    }
                }
            }
            int numeric = Canonical.distance(a, b);
            var fast = CanonicalRepairTraces.fast(a, b);
            var certified = CanonicalRepairTraces.certified(
                    CanonicalAlloyPipeline.prepare(a), CanonicalAlloyPipeline.prepare(b));
            check(numeric == fast.distance() && numeric == certified.distance(),
                    "both-direction numeric/capture/certified equality");
            for (var trace : List.of(fast, certified)) {
                check(trace.replay(trace.json()) == numeric, "selected native replay");
                var corrupt = trace.json().put("distance", -1);
                boolean rejected = false;
                try { trace.replay(corrupt); }
                catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
                check(rejected, "changed native cost rejected");
            }
            var comparison = LocalOwnerTraceTest.compareSelected(a, b, fast, certified);
            check(comparison.status() == NativeOperationComparison.Status.MATCHED,
                    "cross-path selected correspondence: " + comparison.reason());
            check(decisions.get() == null, "public capture removes ThreadLocal");
        }
        System.out.println("MatrixCaptureTest: " + checks + " checks passed");
    }
}
