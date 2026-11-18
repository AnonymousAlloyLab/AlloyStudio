package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.metric.*;

/** Selected declaration ties and unmatched-phase metric presentations. */
public final class NativeDeclarationTraceCoherenceTest {
    private static int checks, negatives;
    private static void check(boolean value,String why) {
        checks++; if(!value)throw new AssertionError(why);
    }
    private static void reject(Runnable action,String why) {
        negatives++;
        try { action.run(); }
        catch(IllegalArgumentException | IllegalStateException expected) { checks++;return; }
        throw new AssertionError("Accepted "+why);
    }
    private static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }
    private static DeclarationReplay.Atom atom(String occurrence) {
        return new DeclarationReplay.Atom(occurrence,List.of("ALL","Component","ONE","0"),"typed-owner");
    }
    private static void backtraceControls() {
        var a=List.of(atom("source/0"));var b=List.of(atom("target/0"),atom("target/1"));
        int[][] dp={{0,1,2},{1,0,1}};
        var old=DeclarationReplay.selectedMoves(dp,(i,j)->0);
        check(old.equals(List.of(new DeclarationReplay.Move(-1,0),new DeclarationReplay.Move(0,1))),"old unconstrained tie retained");
        var link=new DeclarationMatrixLink(List.of("a"),List.of("b","c"),Map.of("a","b"),Set.of(),Set.of());
        var chosen=DeclarationReplay.selectedMoves(a,b,dp,link::allows);
        check(chosen.equals(List.of(new DeclarationReplay.Move(0,0),new DeclarationReplay.Move(-1,1))),"actual map-linked minimum");
        var receipt=DeclarationReplay.captureSelected(a,b,chosen,1);
        check(link.verify(a,b,receipt)==1,"selected map independently checked");
        check(DeclarationReplay.execute(a,b,receipt)==1,"selected program executes");
        var legacy=DeclarationReplay.captureSelected(a,b,old,1);
        reject(()->link.verify(a,b,legacy),"same-cost wrong link");
        var impossible=new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of(),Set.of(),Set.of());
        boolean noPath=false;
        try { DeclarationReplay.selectedMoves(a,b.subList(0,1),new int[][]{{0,1},{1,0}},impossible::allows); }
        catch(DeclarationReplay.NoMatchingPath expected) { noPath=true; }
        check(noPath,"missing joint minimum classified separately");
        reject(()->DeclarationReplay.selectedMoves(a,b,new int[][]{{0}},link::allows),"malformed DP dimensions");
        reject(()->DeclarationReplay.selectedMoves(a,b,new int[][]{{7,1,2},{1,0,1}},link::allows),"changed DP origin");
        for(String mode:List.of("omit","duplicate","order","phase","cost")) {
            var bad=copy(receipt);var ops=bad.getJSONArray("operations");
            switch(mode) {
                case "omit" -> ops.remove(0);
                case "duplicate" -> ops.put(copy(ops.getJSONObject(0)));
                case "order" -> { var first=ops.get(0);ops.put(0,ops.get(1));ops.put(1,first); }
                case "phase" -> ops.getJSONObject(0).getJSONObject("source").put("occurrence","foreign-phase/0");
                case "cost" -> ops.getJSONObject(0).put("cost",1);
            }
            reject(()->DeclarationReplay.execute(a,b,bad),"malformed fixed program: "+mode);
        }
        var oldReceipt=DeclarationReplay.capture(a,b,dp,link::allows);
        check(DeclarationReplay.verify(a,b,oldReceipt)==1,"legacy capture still verifies");
        for(int i=0;i<chosen.size();i++) {
            var op=oldReceipt.getJSONArray("operations").getJSONObject(i);
            check(op.getInt("leftIndex")==chosen.get(i).left() && op.getInt("rightIndex")==chosen.get(i).right(),"shared backtrace, same legacy wire indices");
            check(!op.has("payloadAction"),"legacy capture wire fields unchanged");
        }
    }
    private static Map<String,JSONObject> originalAtoms(JSONObject trace,String side) {
        var result=new TreeMap<String,JSONObject>();
        for(Object item:trace.getJSONArray("nativeDeclarationPrograms"))
            for(Object value:((JSONObject)item).getJSONArray(side)) {
                var atom=(JSONObject)value;
                check(result.put(atom.getString("occurrence"),atom)==null,"unique issued declaration occurrence");
            }
        return result;
    }
    private static void unchangedSelection(JSONObject old,JSONObject now) {
        for(String key:List.of("distance","quantifierDistance","matrixDistance","temporalDistance"))
            check(old.getInt(key)==now.getInt(key),"unchanged component: "+key);
        check(old.getJSONObject("nativeTemporalProgram").similar(now.getJSONObject("nativeTemporalProgram")),"temporal selection unchanged");
        var a=old.getJSONArray("phases");var b=now.getJSONArray("phases");
        check(a.length()==b.length(),"phase selection unchanged");
        for(int i=0;i<a.length();i++) {
            check(a.getJSONObject(i).getJSONObject("receipt").similar(b.getJSONObject(i).getJSONObject("receipt")),"selected matrix derivation and costs unchanged");
            if(a.getJSONObject(i).has("alphaMapping"))
                check(new JSONArray().put(a.getJSONObject(i).get("alphaMapping"))
                        .similar(new JSONArray().put(b.getJSONObject(i).get("alphaMapping"))),"matrix map unchanged");
        }
        for(String side:List.of("source","target")) {
            var before=originalAtoms(old,side);var after=originalAtoms(now,side);
            check(before.keySet().equals(after.keySet()),"all original occurrences retained");
            for(String occurrence:before.keySet())check(before.get(occurrence).similar(after.get(occurrence)),"exact original type/domain/owner/phase/ordinal payload");
        }
    }
    private static void corrupt(NativeRepairTrace trace) {
        var json=trace.json();int component=-1;
        for(int i=0;i<json.getJSONArray("nativeDeclarationPrograms").length();i++)
            if(!json.getJSONArray("nativeDeclarationPrograms").getJSONObject(i).getJSONArray("operations").isEmpty()) {component=i;break;}
        check(component>=0,"nonempty native program exercised");
        for(String mode:List.of("omit","duplicate","phase","type","cost")) {
            var bad=trace.json();var ops=bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(component).getJSONArray("operations");
            var op=ops.getJSONObject(0);
            var endpoint=op.isNull("source")?op.getJSONObject("target"):op.getJSONObject("source");
            switch(mode) {
                case "omit" -> ops.remove(0);
                case "duplicate" -> ops.put(copy(op));
                case "phase" -> endpoint.put("occurrence","foreign-phase");
                case "type" -> endpoint.getJSONArray("tuple").put(1,"ForeignType");
                case "cost" -> op.put("cost",op.getInt("cost")+1);
            }
            reject(()->trace.replay(bad),"tampered native program: "+mode);
        }
    }
    public static void main(String[] args)throws Exception {
        backtraceControls();
        var diagnosis=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var rows=new ArrayList<JSONObject>();
        for(String key:List.of("evidence","regression"))for(Object row:diagnosis.getJSONArray(key))rows.add((JSONObject)row);
        var cache=new HashMap<String,List<Canonical.Prepared>>();var results=new JSONArray();
        for(var row:rows) {
            var identity=row.getJSONObject("identity");String id=identity.getString("id");int direction=row.getInt("direction");
            long start=System.nanoTime();
            if(!cache.containsKey(id))cache.put(id,LocalOwnerTraceTest.prepare(identity));
            var pair=cache.get(id);var a=pair.get(direction);var b=pair.get(1-direction);
            var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var fj=fast.json();var cj=certified.json();
            if(fj.getInt("distance")<=cj.getInt("distance")){fast.replay(fj);certified.replay(cj);}
            else{certified.replay(cj);fast.replay(fj);}
            var old=new JSONObject(Files.readString(Path.of(row.getJSONObject("receipt").getString("file"))));
            unchangedSelection(old.getJSONObject("fast"),fj);unchangedSelection(old.getJSONObject("certified"),cj);
            check(Canonical.distance(a,b)==fj.getInt("distance"),"Fast scalar/capture parity");
            var result=NativeOperationComparison.compare(a,b,fast,certified);
            Files.writeString(out.resolve(id+"-"+direction+".json"),result.evidence().toString(),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,id+"/"+direction+" ordered native actions: "+result.reason());
            corrupt(fast);corrupt(certified);
            double millis=(System.nanoTime()-start)/1e6;check(millis<60_000,"natural direction including controls below60seconds");
            results.put(new JSONObject().put("id",id).put("direction",direction).put("status",result.status().name()).put("milliseconds",millis));
        }
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives).put("results",results).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("NativeDeclarationTraceCoherenceTest: "+checks+" checks; "+negatives+" negative controls; "+rows.size()+" matched directions");
    }
}
