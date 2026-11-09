package is.fivefivefive.CanDis.metric;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Supplemental fixed-representation slices; never mints a public repair plan. */
public final class PhaseReplay {
    private PhaseReplay() { }

    public static JSONObject attach(JSONObject matrix, Supplier<JSONObject> declarations,
            Supplier<JSONObject> temporal) {
        JSONObject q = attempt(declarations), t = attempt(temporal);
        matrix.put("declarationSlice",q).put("temporalSlice",t)
                .put("publicSequenceLinked",false).put("numericalCostCoverage",false);
        if (t.optBoolean("sliceReplayChecked") && t.optInt("cost",-1) == 0
                && t.getJSONArray("operations").isEmpty()) matrix.put("temporalIdentitySlice",t);
        if (q.optBoolean("sliceReplayChecked") && t.optBoolean("sliceReplayChecked")) {
            try {
                JSONArray actions = diagnosticOrder(t,q,matrix.getJSONArray("phases"));
                int cost = verifyDiagnosticOrder(t,q,matrix.getJSONArray("phases"),actions);
                DeclarationReplay.require(cost == matrix.getInt("distance"), "Diagnostic cost coverage");
                matrix.put("orderedDiagnosticActions",actions).put("orderedDiagnosticSequenceChecked",true)
                        .put("orderedDiagnosticCost",cost).put("numericalCostCoverage",true);
            } catch (RuntimeException ex) { matrix.put("orderedDiagnosticBlocker",ex.toString()); }
        }
        return matrix;
    }

    public static JSONArray diagnosticOrder(JSONObject temporal,JSONObject declarations,JSONArray phases) {
        JSONArray actions = new JSONArray();
        JSONArray operations = temporal.getJSONArray("operations");
        DeclarationReplay.require(operations.length() <= 512,"Temporal diagnostic budget");
        for (int i = 0; i < operations.length(); i++) actions.put(new JSONObject()
                .put("phaseKind","temporal").put("receiptPath","/temporalSlice/operations/"+i)
                .put("operation",new JSONObject(operations.getJSONObject(i).toString()))
                .put("cost",DeclarationReplay.integer(operations.getJSONObject(i),"cost")));
        for (Object action : diagnosticOrder(declarations,phases)) actions.put(action);
        return actions;
    }

    public static int verifyDiagnosticOrder(JSONObject temporal,JSONObject declarations,
            JSONArray phases,JSONArray actions) {
        DeclarationReplay.require(diagnosticOrder(temporal,declarations,phases).similar(actions),
                "Temporal/declaration/matrix diagnostic order");
        int cost = 0;
        for (int i = 0; i < actions.length(); i++) {
            int charge = DeclarationReplay.integer(actions.getJSONObject(i),"cost");
            DeclarationReplay.require(charge >= 0,"Negative action cost");
            cost = Math.addExact(cost,charge);
        }
        int expected = Math.addExact(DeclarationReplay.integer(temporal,"cost"),
                verifyDiagnosticOrder(declarations,phases,diagnosticOrder(declarations,phases)));
        DeclarationReplay.require(cost == expected,"Temporal/phase cost conservation");
        return cost;
    }

    private static JSONObject attempt(Supplier<JSONObject> producer) {
        try { return producer.get(); }
        catch (RuntimeException ex) {
            return new JSONObject().put("sliceReplayChecked",false).put("complete",false)
                    .put("status","BLOCKED").put("error",ex.toString());
        }
    }

    /** Supports identity only, not a new approximation of nonzero temporal tree edit distance. */
    public static <N> JSONObject temporalIdentity(N left,N right,int nativeCost,
            Function<N,String> label,Function<N,List<N>> children) {
        JSONArray a = tree(left,label,children,new int[]{131_072},0);
        JSONArray b = tree(right,label,children,new int[]{131_072},0);
        if (nativeCost != 0 || !a.similar(b)) return new JSONObject().put("status","BLOCKED")
                .put("sliceReplayChecked",false).put("complete",false)
                .put("reason","NONIDENTITY_TEMPORAL_REPLAY_NOT_IMPLEMENTED");
        JSONObject receipt = new JSONObject().put("source",new JSONArray(a.toString()))
                .put("target",new JSONArray(b.toString())).put("operations",new JSONArray())
                .put("cost",0).put("replayedCost",0).put("complete",false);
        verifyTemporalIdentity(left,right,label,children,receipt);
        return receipt.put("sliceReplayChecked",true);
    }

    public static <N> void verifyTemporalIdentity(N left,N right,Function<N,String> label,
            Function<N,List<N>> children,JSONObject receipt) {
        JSONArray a = tree(left,label,children,new int[]{131_072},0);
        JSONArray b = tree(right,label,children,new int[]{131_072},0);
        DeclarationReplay.require(a.similar(b) && a.similar(receipt.getJSONArray("source"))
                && b.similar(receipt.getJSONArray("target")), "Temporal identity endpoints");
        DeclarationReplay.require(receipt.getJSONArray("operations").isEmpty()
                && DeclarationReplay.integer(receipt,"cost") == 0
                && DeclarationReplay.integer(receipt,"replayedCost") == 0, "Temporal identity operations/cost");
    }

    private static <N> JSONArray tree(N node,Function<N,String> label,Function<N,List<N>> children,
            int[] budget,int depth) {
        if (depth > 128 || --budget[0] < 0) throw new IllegalStateException("TEMPORAL_REPLAY_BUDGET");
        String head = label.apply(node);
        if (head.length() > budget[0]) throw new IllegalStateException("TEMPORAL_REPLAY_BUDGET");
        budget[0] -= head.length();
        JSONArray values = new JSONArray();
        for (N child : children.apply(node)) values.put(tree(child,label,children,budget,depth+1));
        return new JSONArray().put(head).put(values);
    }

    /** Receipt-addressed diagnostic order: declarations, then each matrix in phase order.
     * This is not claimed to implement edits on the original parsed source. */
    public static JSONArray diagnosticOrder(JSONObject declarations,JSONArray phases) {
        JSONArray actions = new JSONArray();
        int[] budget = {100_000};
        JSONArray components = declarations.getJSONArray("components");
        for (int i = 0; i < components.length(); i++) {
            JSONObject component=components.getJSONObject(i);
            if(component.has("connectingPermutation")) {
                if(--budget[0]<0) throw new IllegalStateException("DIAGNOSTIC_ORDER_BUDGET");
                JSONObject permutation=component.getJSONObject("connectingPermutation");
                DeclarationReplay.require(DeclarationReplay.integer(permutation,"cost")==0,"Connecting permutation charge");
                actions.put(new JSONObject().put("phaseKind","declarationPermutation")
                        .put("receiptPath","/declarationSlice/components/"+i+"/connectingPermutation")
                        .put("operation",new JSONObject(permutation.toString())).put("cost",0));
            }
            JSONArray ops = component.getJSONObject("receipt").getJSONArray("operations");
            for (int j = 0; j < ops.length(); j++) {
                if (--budget[0] < 0) throw new IllegalStateException("DIAGNOSTIC_ORDER_BUDGET");
                actions.put(new JSONObject().put("phaseKind","declaration")
                        .put("receiptPath","/declarationSlice/components/"+i+"/receipt/operations/"+j)
                        .put("operation",new JSONObject(ops.getJSONObject(j).toString()))
                        .put("cost",DeclarationReplay.integer(ops.getJSONObject(j),"cost")));
            }
        }
        for (int i = 0; i < phases.length(); i++) flatten(
                phases.getJSONObject(i).getJSONObject("receipt").getJSONObject("derivation"),
                "/phases/"+i+"/receipt/derivation",actions,budget,0);
        return actions;
    }

    private static void flatten(JSONObject node,String path,JSONArray actions,int[] budget,int depth) {
        if (depth > 128 || --budget[0] < 0) throw new IllegalStateException("DIAGNOSTIC_ORDER_BUDGET");
        String kind = node.getString("kind");
        JSONObject event = new JSONObject().put("phaseKind","matrix").put("receiptPath",path)
                .put("kind",kind).put("sourceHead",node.getString("sourceHead"))
                .put("targetHead",node.getString("targetHead"))
                .put("sourceSha256",node.getString("sourceSha256"))
                .put("targetSha256",node.getString("targetSha256"));
        if (kind.equals("select")) {
            actions.put(event.put("cost",0).put("leftAlternative",node.getInt("leftAlternative"))
                    .put("rightAlternative",node.getInt("rightAlternative")));
            flatten(node.getJSONObject("selected"),path+"/selected",actions,budget,depth+1);
        } else if (kind.equals("node")) {
            actions.put(event.put("cost",DeclarationReplay.integer(node,"headCost")));
            JSONArray children = node.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) flatten(children.getJSONObject(i).getJSONObject("edit"),
                    path+"/children/"+i+"/edit",actions,budget,depth+1);
        } else {
            DeclarationReplay.require(kind.equals("insert") || kind.equals("delete"), "Matrix operation kind");
            actions.put(event.put("cost",DeclarationReplay.integer(node,"cost")));
        }
    }

    /** Checks exact order and receipt references, conditional on the individual slice verifiers. */
    public static int verifyDiagnosticOrder(JSONObject declarations,JSONArray phases,JSONArray actions) {
        DeclarationReplay.require(diagnosticOrder(declarations,phases).similar(actions), "Diagnostic sequence linkage/order");
        int total = 0;
        for (int i = 0; i < actions.length(); i++) {
            int cost = DeclarationReplay.integer(actions.getJSONObject(i),"cost");
            DeclarationReplay.require(cost >= 0,"Negative action cost");
            total = Math.addExact(total,cost);
        }
        int expected = DeclarationReplay.integer(declarations,"cost");
        for (int i = 0; i < phases.length(); i++) expected = Math.addExact(expected,
                DeclarationReplay.integer(phases.getJSONObject(i).getJSONObject("receipt"),"cost"));
        DeclarationReplay.require(total == expected,"Receipt-to-sequence cost conservation");
        return total;
    }
}
