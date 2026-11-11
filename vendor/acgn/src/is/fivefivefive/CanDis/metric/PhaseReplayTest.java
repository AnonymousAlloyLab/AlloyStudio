package is.fivefivefive.CanDis.metric;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PhaseReplayTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void reject(Runnable operation) {
        checks++;
        try { operation.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Accepted invalid receipt " + checks);
    }
    private static DeclarationReplay.Atom atom(String id,String type) {
        return new DeclarationReplay.Atom(id,List.of("ALL",type,"ONE","0"),"context");
    }
    private static JSONObject copy(JSONObject object) { return new JSONObject(object.toString()); }
    private static int[][] dp(List<DeclarationReplay.Atom> a,List<DeclarationReplay.Atom> b) {
        int[][] result = new int[a.size()+1][b.size()+1];
        for (int i = 0; i <= a.size(); i++) result[i][0] = i;
        for (int j = 0; j <= b.size(); j++) result[0][j] = j;
        for (int i = 1; i <= a.size(); i++) for (int j = 1; j <= b.size(); j++) result[i][j] = Math.min(
                result[i-1][j-1] + (a.get(i-1).tuple().equals(b.get(j-1).tuple()) ? 0 : 1),
                Math.min(result[i-1][j]+1,result[i][j-1]+1));
        return result;
    }
    private record Tree(String label,List<Tree> children) { }

    public static void main(String[] args) {
        var a = List.of(atom("source0","Class"),atom("source1","Person"));
        List<DeclarationReplay.Atom> empty = List.of();
        JSONObject deletion = DeclarationReplay.capture(a,empty,dp(a,empty),(i,j)->true);
        check(DeclarationReplay.verify(a,empty,deletion) == 2);
        JSONObject insertion = DeclarationReplay.capture(empty,a,dp(empty,a),(i,j)->true);
        check(DeclarationReplay.verify(empty,a,insertion) == 2);
        JSONObject omitted = copy(deletion);
        omitted.getJSONArray("operations").remove(1);
        reject(()->DeclarationReplay.verify(a,empty,omitted));
        JSONObject repeated = copy(deletion);
        repeated.getJSONArray("operations").getJSONObject(1).put("leftIndex",0);
        reject(()->DeclarationReplay.verify(a,empty,repeated));
        JSONObject reordered = copy(deletion);
        reordered.getJSONArray("source").put(0,a.get(1).json()).put(1,a.get(0).json());
        reject(()->DeclarationReplay.verify(a,empty,reordered));
        JSONObject endpoint = copy(deletion);
        endpoint.getJSONArray("source").getJSONObject(0).put("admission","substituted");
        endpoint.getJSONArray("operations").getJSONObject(0).getJSONObject("source").put("admission","substituted");
        reject(()->DeclarationReplay.verify(a,empty,endpoint));
        check(DeclarationReplay.verify(a,empty,deletion) == 2);
        JSONObject overflow = copy(deletion).put("cost",4_294_967_298L);
        reject(()->DeclarationReplay.verify(a,empty,overflow));
        JSONObject fractional = copy(deletion).put("cost",2.5);
        reject(()->DeclarationReplay.verify(a,empty,fractional));
        var b = List.of(atom("target0","Other"));
        JSONObject chosen = DeclarationReplay.capture(a,b,dp(a,b),(i,j)->j < 0 ? i == 1 : i == 0);
        check(chosen.getJSONArray("operations").getJSONObject(0).getInt("leftIndex") == 0);
        check(chosen.getJSONArray("operations").getJSONObject(0).getString("kind").equals("update"));
        check(DeclarationReplay.verify(a,b,chosen) == 2);
        reject(()->DeclarationReplay.capture(a,b,dp(a,b),(i,j)->false));
        var equalTuple = List.of(atom("differentOccurrence","Class"));
        JSONObject keep = DeclarationReplay.capture(a.subList(0,1),equalTuple,dp(a.subList(0,1),equalTuple),(i,j)->true);
        check(keep.getInt("cost") == 0);
        reject(()->DeclarationReplay.verify(a.subList(0,1),List.of(atom("wrongOccurrence","Class")),keep));
        DeclarationReplay.requireBounded(256,256); // Resource-accounted cells, not a binder-count exclusion.
        check(true);
        reject(()->DeclarationReplay.requireBounded(Integer.MAX_VALUE,Integer.MAX_VALUE));
        reject(()->DeclarationReplay.capture(java.util.Collections.nCopies(300,a.get(0)),empty,new int[0][],(i,j)->true));
        Tree root = new Tree("NONE",List.of(new Tree("ALWAYS",List.of())));
        JSONObject identity = PhaseReplay.temporalIdentity(root,root,0,Tree::label,Tree::children);
        check(identity.getBoolean("sliceReplayChecked"));
        PhaseReplay.verifyTemporalIdentity(root,root,Tree::label,Tree::children,identity);
        JSONObject changed = copy(identity);
        changed.getJSONArray("source").put(0,"EVENTUALLY");
        changed.getJSONArray("target").put(0,"EVENTUALLY");
        reject(()->PhaseReplay.verifyTemporalIdentity(root,root,Tree::label,Tree::children,changed));
        check(!PhaseReplay.temporalIdentity(root,new Tree("OTHER",List.of()),1,Tree::label,Tree::children)
                .getBoolean("sliceReplayChecked"));
        JSONObject q = new JSONObject().put("cost",2).put("components",new JSONArray().put(
                new JSONObject().put("receipt",deletion)));
        JSONArray actions = PhaseReplay.diagnosticOrder(q,new JSONArray());
        check(PhaseReplay.verifyDiagnosticOrder(q,new JSONArray(),actions) == 2);
        JSONArray wrongOrder = new JSONArray().put(actions.getJSONObject(1)).put(actions.getJSONObject(0));
        reject(()->PhaseReplay.verifyDiagnosticOrder(q,new JSONArray(),wrongOrder));
        actions.getJSONObject(0).getJSONObject("operation").put("cost",0);
        reject(()->PhaseReplay.verifyDiagnosticOrder(q,new JSONArray(),actions));
        check(DeclarationReplay.verify(a,empty,deletion) == 2);
        Tree collapsed = new Tree("NONE",List.of());
        JSONObject temporal = TemporalReplay.capture(root,collapsed,1,Tree::label,Tree::children);
        JSONObject attached = PhaseReplay.attach(new JSONObject().put("distance",3)
                .put("phases",new JSONArray()),() -> copy(q).put("sliceReplayChecked",true),() -> temporal);
        check(attached.getBoolean("numericalCostCoverage"));
        check(attached.getInt("orderedDiagnosticCost") == 3);
        check(!attached.has("temporalIdentitySlice"));
        JSONArray all = attached.getJSONArray("orderedDiagnosticActions");
        check(all.getJSONObject(0).getString("phaseKind").equals("temporal"));
        JSONArray swapped = new JSONArray().put(all.getJSONObject(1)).put(all.getJSONObject(0))
                .put(all.getJSONObject(2));
        reject(() -> PhaseReplay.verifyDiagnosticOrder(temporal,q,new JSONArray(),swapped));
        System.out.println("PhaseReplayTest: " + checks + " checks passed");
    }
}
