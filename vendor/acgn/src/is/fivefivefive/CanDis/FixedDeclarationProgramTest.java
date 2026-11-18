package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Selected paid programs remain immutable; completion checks native costs. */
public final class FixedDeclarationProgramTest {
    private static int checks, negatives;
    private static void check(boolean value, String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    private static Method method(String name) {
        var methods = Arrays.stream(CanonicalDistance.class.getDeclaredMethods()).filter(m -> m.getName().equals(name)).toList();
        check(methods.size() == 1, "Unique native method " + name);
        var result = methods.get(0); result.setAccessible(true); return result;
    }
    private static CanonicalDistance.Prepared delegate(Canonical.Prepared value) throws Exception {
        var field = value.getClass().getDeclaredField("delegate"); field.setAccessible(true);
        return (CanonicalDistance.Prepared)field.get(value);
    }
    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static void rejected(Attempt attempt, String why) throws Exception {
        boolean rejected = false;
        try { attempt.run(); }
        catch (InvocationTargetException e) { rejected = e.getCause() instanceof IllegalArgumentException || e.getCause() instanceof IllegalStateException; }
        catch (IllegalArgumentException | IllegalStateException e) { rejected = true; }
        negatives++; check(rejected, why);
    }
    private static List<EGraphNode> nodes(EGraphNode root) {
        var result = new ArrayList<EGraphNode>(); var seen = Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        var queue = new ArrayDeque<EGraphNode>(); queue.add(root);
        while (!queue.isEmpty()) { var node = queue.removeFirst(); if (!seen.add(node)) continue; result.add(node); queue.addAll(node.getChildren()); }
        return result;
    }
    private static void costs(Canonical.Prepared endpoint, Canonical.Prepared foreign) throws Exception {
        var prepared = delegate(endpoint); var other = delegate(foreign);
        var local = nodes(prepared.comparisonForms().get(0).getMatrixEGraph()).stream()
                .filter(n -> n.getOpcode() == EGraphNode.Opcode.VARIABLE && n.hasComparisonLocalCoordinate()).findFirst().orElseThrow();
        var choice = MatrixDecision.node(local, local, 0, 0, false, List.of());
        var unchanged = method("unchangedSelectedCharges");
        check((Boolean)unchanged.invoke(null, choice, Map.of(), Map.of(), prepared, prepared), "Original local identity cost");
        check(!(Boolean)unchanged.invoke(null, choice, Map.of(), Map.of("foreign", local.getAlphaName()), prepared, prepared),
                "Target collision changes local fallback even without the new source-target read"); negatives++;
        var selected = MatrixDecision.select(local, local, 0, 0, choice);
        check(!(Boolean)unchanged.invoke(null, selected, Map.of(), Map.of("foreign", local.getAlphaName()), prepared, prepared),
                "Selected alternative is checked too"); negatives++;
        var wrongCost = MatrixDecision.node(local, local, 1, 1, false, List.of());
        rejected(() -> unchanged.invoke(null, wrongCost, Map.of(), Map.of(), prepared, prepared), "Wrong original primitive charge");
        var original = prepared.comparisonForms().get(0).getMatrixQuantiVars();
        var frames = method("completionFrames");
        check(((JSONArray)frames.invoke(null, prepared, 0, original)).length() == original.size(), "All original declaration frames");
        rejected(() -> frames.invoke(null, prepared, 0, other.comparisonForms().get(0).getMatrixQuantiVars()), "Foreign frozen binding objects");
    }
    public static void main(String[] args) throws Exception {
        var retained = Path.of(args[0]); var output = Path.of(args[1]); Files.createDirectory(output);
        var old0 = new JSONObject(Files.readString(retained.resolve("event41-0.json"))); var identity = old0.getJSONObject("identity");
        var pair = LocalOwnerTraceTest.prepare(identity); var results = new JSONArray();
        for (int direction = 0; direction < 2; direction++) {
            var old = new JSONObject(Files.readString(retained.resolve("event41-"+direction+".json")));
            var a = pair.get(direction); var b = pair.get(1-direction);
            var fast = CanonicalRepairTraces.fast(a,b); var certified = CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var json = fast.json();
            check(fast.replay(json) == 29 && certified.replay(certified.json()) == 29, "Actual both-arm replay and retained costs");
            for (String part : List.of("nativeDeclarationPrograms","nativeTemporalProgram","phases"))
                check(Objects.requireNonNull(json.opt(part)).toString().equals(old.getJSONObject("fast").get(part).toString()), "Original native selection unchanged: " + part);
            var result = NativeOperationComparison.compare(a,b,fast,certified);
            check(result.status() == NativeOperationComparison.Status.MATCHED, "Real immutable trace comparison: " + result.reason() + " " + result.evidence().opt("error"));
            var component = json.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0);
            var receipt = component.getJSONObject("receipt"); var completion = receipt.getJSONObject("matrixCorrespondenceCompletion");
            var original = new JSONObject(receipt.toString()); original.remove("matrixCorrespondenceCompletion");
            check(original.similar(json.getJSONArray("nativeDeclarationPrograms").getJSONObject(0)), "Exact actual declaration program, not another minimum");
            check(completion.getBoolean("selectedDeclarationProgramUnchanged") && completion.getBoolean("matrixActionsUnchanged"), "Fixed selected programs checked");
            check(completion.getJSONArray("checkedMatrixPhases").length() == json.getJSONArray("phases").length(), "Complete matrix phase census");
            var authorized=completion.getJSONArray("checkedMatrixPhases").getJSONObject(0).getJSONArray("declarationLinks");
            check(!authorized.isEmpty(), "Actual original-object completion links");
            for(Object item:authorized) {
                var link=(JSONObject)item;var operation=receipt.getJSONArray("operations").getJSONObject(link.getInt("operationIndex"));
                check(link.getInt("declarationPhase")==0 && operation.getInt("leftIndex")>=0 && operation.getInt("rightIndex")>=0,
                        "Completion names an actual selected diagonal, not a separate payment");
                check(completion.getJSONObject("completedMapping").getString(link.getString("source")).equals(link.getString("target")),
                        "Original-object link is the completed map entry");
            }
            check(!completion.getJSONObject("paidCorrespondence").isEmpty(), "Actual paid declaration authority");
            for (String mode : List.of("source","owner","domain","mapping","cost","same-cost-program","missing-phase")) {
                var bad = fast.json(); var changed = bad.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt");
                var proof = changed.getJSONObject("matrixCorrespondenceCompletion");
                switch (mode) {
                    case "source" -> changed.getJSONArray("source").getJSONObject(0).put("occurrence", "foreign");
                    case "owner" -> proof.getJSONArray("sourceBindingFrames").getJSONObject(0).put("owner", "foreign");
                    case "domain" -> proof.getJSONArray("sourceBindingFrames").getJSONObject(0).getJSONArray("frames").getJSONObject(0).put("domain", "foreign");
                    case "mapping" -> proof.put("completedMapping", new JSONObject());
                    case "cost" -> changed.getJSONArray("operations").getJSONObject(0).put("cost", 0);
                    case "same-cost-program" -> {
                        var ops = bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(0).getJSONArray("operations");
                        var first = ops.get(0); ops.put(0,ops.get(1)); ops.put(1,first);
                    }
                    case "missing-phase" -> proof.put("checkedMatrixPhases", new JSONArray());
                }
                rejected(() -> fast.replay(bad), "Immutable trace rejects " + mode);
            }
            check(fast.replay(fast.json()) == 29, "Original trace retained after negatives");
            Files.writeString(output.resolve("event41-"+direction+".json"),result.evidence().toString(),StandardOpenOption.CREATE_NEW);
            results.put(new JSONObject().put("direction", direction).put("status", result.status()).put("distance", fast.distance()));
        }
        costs(pair.get(0),pair.get(1));
        var temporalIdentity = new JSONArray(Files.readString(Path.of(args[2]))).getJSONObject(0);
        var temporal = delegate(LocalOwnerTraceTest.prepare(temporalIdentity).get(0));
        int phase = -1;
        for (int i=0;i<temporal.comparisonForms().size();i++) if (!temporal.comparisonForms().get(i).getInheritedQuantiVars().isEmpty()) { phase=i; break; }
        check(phase>=0, "Actual retained temporal import");
        var form = temporal.comparisonForms().get(phase); var imported = form.getInheritedQuantiVars().get(0);
        int owner=-1;
        for(int i=0;i<temporal.comparisonForms().size();i++)
            if(temporal.comparisonForms().get(i).getMatrixQuantiVars().stream().anyMatch(q->q==imported)) { owner=i;break; }
        check(owner>=0,"Import has original declaration owner");
        var visible=method("completionVisible");
        check((Boolean)visible.invoke(null,temporal,owner,owner,imported),"Original owner resolves by identity");
        check((Boolean)visible.invoke(null,temporal,owner,phase,imported),"Original inherited binding resolves in its descendant");
        check(!(Boolean)visible.invoke(null,temporal,phase,phase,imported),"Use phase cannot claim imported declaration ownership");negatives++;
        var forged=new QuantiVar(imported.getId(),imported.getName(),imported.getOriginalName(),imported.getTypeName());
        for(var field:QuantiVar.class.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&!Modifier.isFinal(field.getModifiers())) {
            field.setAccessible(true);field.set(forged,field.get(imported));
        }
        check(!(Boolean)visible.invoke(null,temporal,owner,phase,forged),"Identical serialized metadata cannot issue original identity");negatives++;
        check(!(Boolean)visible.invoke(null,temporal,owner,temporal.comparisonForms().size(),imported),"Absent phase is not an import");negatives++;
        var use = nodes(form.getMatrixEGraph()).stream().filter(n -> n.getOpcode()==EGraphNode.Opcode.VARIABLE && imported.getName().equals(n.getAlphaName())).findFirst().orElseThrow();
        var read = MatrixDecision.node(use,use,0,0,false,List.of());
        check(!(Boolean)method("unchangedSelectedCharges").invoke(null,read,Map.of(imported.getName(),imported.getName()),Map.of(),temporal,temporal), "Imported selected read cannot lose its binding charge"); negatives++;
        final int usePhase=phase;
        rejected(() -> method("completionFrames").invoke(null,temporal,usePhase,List.of(imported)), "An import is not a declaration owned by its use phase");
        Files.writeString(output.resolve("results.json"),new JSONObject().put("checks",checks).put("negatives",negatives).put("results",results)
                .put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("FixedDeclarationProgramTest: "+checks+" checks, "+negatives+" negatives");
    }
}
