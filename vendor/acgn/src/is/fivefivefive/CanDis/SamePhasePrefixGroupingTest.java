package is.fivefivefive.CanDis;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.QuantiVar;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

public final class SamePhasePrefixGroupingTest {
    private static int checks;
    private static int negatives;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static Canonical.Prepared prepare(String text) throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, text);
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of("p"), module);
        visitor.visit(new ModelUnit(null, module), null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId("p")));
    }
    private static Canonical.Prepared source(String file, String predicate) throws Exception {
        var module = AlloyUtil.compileAlloyModule(file);
        var visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(predicate)));
    }
    @SuppressWarnings("unchecked")
    private static List<QuantiVar> order(NormalForm form) throws Exception {
        Method method = CanonicalDistance.class.getDeclaredMethod("canonicalQuantifierOrder", List.class);
        method.setAccessible(true);
        return (List<QuantiVar>) method.invoke(null, form.getMatrixQuantiVars());
    }
    private static boolean admitted(NormalForm a, NormalForm b) throws Exception {
        Method method = Class.forName("is.fivefivefive.CanDis.core.FastSamePhasePrefix")
                .getDeclaredMethod("matches", NormalForm.class, NormalForm.class, List.class, List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, a, b, order(a), order(b));
    }
    private static void corrupt(Object object, String name, Object value,
            NormalForm a, NormalForm b) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object old = field.get(object);
        try {
            field.set(object, value);
            negatives++;
            check(!admitted(a, b), "forged " + name);
        } finally { field.set(object, old); }
    }
    private static void variants() throws Exception {
        String header = "sig A { r: B -> C } sig B {} sig C {} pred p { ";
        for (String quantifier : List.of("all", "some"))
            for (String barrier : List.of("lone", "one"))
                for (boolean temporal : List.of(false, true)) {
                    String open = temporal ? "always { " : "", close = temporal ? " }" : "";
                    var a = prepare(header + open + quantifier + " a:A | " + quantifier
                            + " b:B | " + barrier + " c:C | b->c in a.r" + close + " }");
                    var b = prepare(header + open + quantifier + " b:B, a:A | " + barrier
                            + " c:C | b->c in a.r" + close + " }");
                    String work = System.getProperty("acgn.maxCanonicalWorkItems");
                    try {
                        System.setProperty("acgn.maxCanonicalWorkItems", "1");
                        check(Canonical.distance(a, b) == 0 && Canonical.distance(b, a) == 0,
                                "Fast-only grouped " + quantifier + "/" + barrier + "/" + temporal);
                    } finally {
                        if (work == null) System.clearProperty("acgn.maxCanonicalWorkItems");
                        else System.setProperty("acgn.maxCanonicalWorkItems", work);
                    }
                    var ca = CanonicalAlloyPipeline.prepare(a);
                    var cb = CanonicalAlloyPipeline.prepare(b);
                    check(CanonicalAlloyPipeline.distance(ca, cb) == 0, "certified grouped variant");
                }
        var a = prepare(header + "all a:A | all b:B | lone c:C | b->c in a.r }");
        var b = prepare(header + "all a:A,b:B | lone c:C | b->c in a.r }");
        var af = a.normalizedForms().get(0);
        var bf = b.normalizedForms().get(0);
        check(admitted(af, bf), "authenticated presentation");
        var q = bf.getMatrixQuantiVars().get(2);
        corrupt(q, "bindingPath", "foreign", af, bf);
        corrupt(q, "deBruijnKey", "foreign", af, bf);
        corrupt(q, "quantifier", QuantiVar.Quantifier.ONE, af, bf);
        corrupt(q, "cardinality", QuantiVar.Cardinality.SET, af, bf);
        corrupt(q, "exactAlloyType", bf.getMatrixQuantiVars().get(0).getExactAlloyType(), af, bf);
        corrupt(bf, "phasePath", "phase[99]", af, bf);
        corrupt(bf, "temporalOp", NormalForm.TemporalOp.ALWAYS, af, bf);
        check(admitted(af, bf), "tampering restored");
        String rigid = "sig A { r:set A } pred p { ";
        for (String kind : List.of("one", "lone")) {
            var grouped = prepare(rigid + kind + " x,y:A | x in y.r }").normalizedForms().get(0);
            var nested = prepare(rigid + kind + " x:A | " + kind + " y:A | x in y.r }").normalizedForms().get(0);
            negatives++;
            check(!admitted(grouped, nested), "rigid tuple remains ordered " + kind);
        }
        var outside = prepare(header + "all a:A | always (all b:B | lone c:C | b->c in a.r) }");
        var inside = prepare(header + "always (all a:A,b:B | lone c:C | b->c in a.r) }");
        negatives++;
        check(!admitted(outside.normalizedForms().get(0), inside.normalizedForms().get(0)),
                "no temporal scope movement");
    }
    private static void invalid(Canonical.Prepared a, Canonical.Prepared b, JSONObject evidence, String why) {
        negatives++;
        check(NativeOperationComparison.compareCaptured(a, b, evidence).status()
                == NativeOperationComparison.Status.INVALID_REPLAY, why);
    }
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        Files.createDirectory(out);
        var incident = new JSONObject(Files.readString(Path.of(args[0])));
        var prepared = new HashMap<String, Canonical.Prepared>();
        var rows = new JSONArray();
        int index = 0;
        for (Object value : incident.getJSONArray("records")) {
            long start = System.nanoTime();
            var row = (JSONObject) value;
            var pair = new ArrayList<Canonical.Prepared>();
            for (Object reference : row.getJSONArray("references")) {
                String file = ((JSONObject) reference).getString("source");
                if (!prepared.containsKey(file)) prepared.put(file, source(args[2] + "/" + file, "inv12"));
                pair.add(prepared.get(file));
            }
            for (int d = 0; d < 2; d++) {
                var a = pair.get(d);
                var b = pair.get(1-d);
                var aq = a.normalizedForms().get(0).getMatrixQuantiVars();
                var bq = b.normalizedForms().get(0).getMatrixQuantiVars();
                check(!aq.get(2).getBindingPath().equals(bq.get(2).getBindingPath()), "exact old path gate rejects");
                check(admitted(a.normalizedForms().get(0), b.normalizedForms().get(0)), "new typed presentation accepts");
                var paths = aq.stream().map(QuantiVar::getBindingPath).toList();
                var cert = CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a), CanonicalAlloyPipeline.prepare(b));
                var fast = CanonicalRepairTraces.fast(a,b);
                cert.replay(cert.json()); fast.replay(fast.json());
                var result = NativeOperationComparison.compare(a,b,fast,cert);
                Files.writeString(out.resolve(index + "-" + d + ".json"), result.evidence().toString(2), StandardOpenOption.CREATE_NEW);
                check(fast.distance() == 0 && cert.distance() == 0 && Canonical.distance(a,b) == 0, "natural zero kernel");
                check(result.status() == NativeOperationComparison.Status.MATCHED, result.status() + " " + result.reason());
                check(paths.equals(aq.stream().map(QuantiVar::getBindingPath).toList()), "source coordinates retained");
                var diagnostic = result.evidence().getJSONObject("diagnostic");
                check(NativeOperationComparison.compareCaptured(a,b,diagnostic).status()
                        == NativeOperationComparison.Status.MATCHED, "independent selected replay");
                var bad = new JSONObject(diagnostic.toString());
                bad.getJSONArray("nativeOperationEndpoints").put(0, "foreign");
                invalid(a,b,bad,"foreign endpoint");
                bad = new JSONObject(diagnostic.toString());
                bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0)
                        .getJSONObject("receipt").put("cost",1);
                invalid(a,b,bad,"forged matrix cost");
                bad = new JSONObject(diagnostic.toString());
                bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0)
                        .getJSONObject("alphaMapping").remove(aq.get(2).getName());
                invalid(a,b,bad,"missing rigid slot transport");
                rows.put(new JSONObject().put("pair",index).put("direction",d).put("status",result.status()).put("distance",0));
            }
            check((System.nanoTime()-start)/1e9 < 60, "natural pair below60s");
            index++;
        }
        for (var file : prepared.keySet()) {
            var oracle = source(args[2] + "/" + file, "inv12c");
            var student = prepared.get(file);
            check(Canonical.distance(student,oracle) == 9 && Canonical.distance(oracle,student) == 9, "old oracle distances");
            for (var pair : List.of(List.of(student, oracle), List.of(oracle, student))) {
                var a = pair.get(0); var b = pair.get(1);
                var cert = CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a), CanonicalAlloyPipeline.prepare(b));
                var fast = CanonicalRepairTraces.fast(a,b);
                check(NativeOperationComparison.compare(a,b,fast,cert).status()
                        == NativeOperationComparison.Status.MATCHED, "prior oracle native path");
            }
        }
        variants();
        Files.writeString(out.resolve("checks.json"), new JSONObject().put("checks",checks)
                .put("negatives",negatives).put("directions",rows).toString(2), StandardOpenOption.CREATE_NEW);
        System.out.println("SamePhasePrefixGroupingTest: " + checks + " checks, " + negatives + " negative controls");
    }
}
