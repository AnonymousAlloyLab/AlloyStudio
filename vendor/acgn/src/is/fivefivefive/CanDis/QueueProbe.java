package is.fivefivefive.CanDis;
import java.nio.file.*;import java.util.*;import org.json.*;
public class QueueProbe {
 static void retainFailure(JSONObject evidence,Throwable failure,String stage) {
  evidence.put("error",failure.toString()).put("failureStage",stage);
  if(failure instanceof is.fivefivefive.CanDis.metric.NativeRepairTrace.CaptureFailure capture)evidence.put("partialNativeReceipt",capture.selectedReceipt());
 }
 static void orderedReplay(int fastCost,int certifiedCost,Runnable fast,Runnable certified,JSONObject evidence) {
  boolean fastFirst=fastCost<=certifiedCost;
  evidence.put("replayOrder",new JSONArray().put(fastFirst?"fast":"certified").put(fastFirst?"certified":"fast"));
  evidence.put("replayTieRule","FAST_FIRST");
  var completed=new JSONArray();evidence.put("replayCompleted",completed);
  evidence.put("replayStarted",fastFirst?"fast":"certified");
  (fastFirst?fast:certified).run();completed.put(fastFirst?"fast":"certified");
  evidence.put("replayStarted",fastFirst?"certified":"fast");
  (fastFirst?certified:fast).run();completed.put(fastFirst?"certified":"fast");
 }
 public static void main(String[] args)throws Exception { run(args,(result,evidence)->result); }
 static void run(String[] args,java.util.function.BiFunction<NativeOperationComparison.Result,JSONObject,NativeOperationComparison.Result> comparisonControl)throws Exception {
  run(args,comparisonControl,(stage,evidence)->{});
 }
 static void run(String[] args,java.util.function.BiFunction<NativeOperationComparison.Result,JSONObject,NativeOperationComparison.Result> comparisonControl,
   java.util.function.BiConsumer<String,JSONObject> beforeStage)throws Exception {
  var ids=new JSONArray(Files.readString(Path.of(args[0])));Path out=Path.of(args[1]);Files.createDirectory(out);
  var cache=new HashMap<String,List<Canonical.Prepared>>();var rows=new JSONArray();
  for(Object value:ids) {
   var row=(JSONObject)value;var id=row.getJSONObject("identity");int d=row.getInt("direction");String key=id.getString("source")+id.getString("left")+id.getString("right");
   var evidence=new JSONObject().put("identity",id).put("direction",d);String name=id.getString("id")+"-"+d+".json";String stage="prepare";
   try {
    beforeStage.accept(stage,evidence);
    if(!cache.containsKey(key))cache.put(key,LocalOwnerTraceTest.prepare(id));var pair=cache.get(key);var a=pair.get(d);var b=pair.get(1-d);
    stage="certified";beforeStage.accept(stage,evidence);var c=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));var cj=c.json();evidence.put("certified",cj);
    stage="fast";beforeStage.accept(stage,evidence);var f=CanonicalRepairTraces.fast(a,b);var fj=f.json();evidence.put("fast",fj);
    Files.writeString(out.resolve(id.getString("id")+"-"+d+"-selected.json"),evidence.toString(),StandardOpenOption.CREATE_NEW);
    stage="ordered-native-replay";orderedReplay(f.distance(),c.distance(),
      ()->{beforeStage.accept("replay-fast",evidence);f.replay(fj);},
      ()->{beforeStage.accept("replay-certified",evidence);c.replay(cj);},evidence);
    stage="comparison";var result=comparisonControl.apply(NativeOperationComparison.compare(a,b,f,c),evidence);evidence.put("comparison",result.evidence());
    rows.put(new JSONObject().put("identity",id).put("direction",d).put("status",result.status()).put("reason",result.reason()).put("fast",f.distance()).put("certified",c.distance()).put("receipt",name));
    System.out.println(name+" "+f.distance()+"/"+c.distance()+" "+result.status()+" "+result.reason()+" "+result.evidence().opt("correspondenceBoundary"));
   } catch(Throwable failure) {
    retainFailure(evidence,failure,stage);
    var outcome=NativeOperationComparison.failure(failure,null);
    evidence.put("failureProvenance",outcome.evidence()).put("nativeReplayComplete",false);
    Files.writeString(out.resolve(name),evidence.toString(),StandardOpenOption.CREATE_NEW);
    rows.put(new JSONObject().put("identity",id).put("direction",d).put("status",outcome.status()).put("reason",outcome.reason())
      .put("stage",stage).put("error",failure.toString()).put("receipt",name).put("nativeReplayComplete",false)
      .put("fast",evidence.has("fast")?evidence.getJSONObject("fast").get("distance"):JSONObject.NULL)
      .put("certified",evidence.has("certified")?evidence.getJSONObject("certified").get("distance"):JSONObject.NULL));
    Files.writeString(out.resolve("results.json"),rows.toString(2),StandardOpenOption.CREATE_NEW);
    if(failure instanceof VirtualMachineError)throw failure;
    throw new IllegalStateException("QUEUE_FIRST_NONMATCH: "+name+" "+outcome.status(),failure);
   }
   Files.writeString(out.resolve(name),evidence.toString(),StandardOpenOption.CREATE_NEW);
   if(!evidence.getJSONObject("comparison").getString("status").equals("MATCHED")) {
    Files.writeString(out.resolve("results.json"),rows.toString(2),StandardOpenOption.CREATE_NEW);
    throw new IllegalStateException("QUEUE_FIRST_NONMATCH: "+name+" "+evidence.getJSONObject("comparison").getString("status"));
   }
  }
  Files.writeString(out.resolve("results.json"),rows.toString(2),StandardOpenOption.CREATE_NEW);
 }
}
