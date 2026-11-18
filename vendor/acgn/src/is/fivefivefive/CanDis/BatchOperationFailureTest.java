package is.fivefivefive.CanDis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.json.JSONObject;
import is.fivefivefive.CanDis.metric.ReplayValidationException;
import is.fivefivefive.CanDis.metric.TraceResources;
import is.fivefivefive.CanDis.metric.PhaseReplay;
import is.fivefivefive.CanDis.metric.RepairProjection;
import org.json.JSONArray;

/** Provenance and fatal-stop controls for the outer operation-consumer boundary. */
public final class BatchOperationFailureTest {
    private static int checks;
    private static final int[] ZERO={0,0,0,0};
    private static void check(boolean condition,String message) {
        checks++;if(!condition)throw new AssertionError(message);
    }
    private static void stopped(Runnable action) {
        try { action.run();throw new AssertionError("Fatal failure continued"); }
        catch(BatchParityGate.Violation expected) { checks++; }
    }
    private static JSONObject read(Path path)throws IOException {
        return new JSONObject(Files.readString(path));
    }
    private static RuntimeException exhaustion() {
        try { TraceResources.requireBytes(Long.MAX_VALUE);throw new AssertionError("Missing accounting refusal"); }
        catch(TraceResources.Exhausted expected) { return expected; }
    }
    private static void checkerLimit(Runnable action,String origin) {
        try { action.run();throw new AssertionError("Missing actual checker refusal: "+origin); }
        catch(TraceResources.Exhausted failure) {
            check(NativeOperationComparison.failure(failure,null).status()==NativeOperationComparison.Status.UNCHECKABLE,
                    origin+" is an unresolved checker limitation");
        }
    }
    private static void projectionLimit(String name,Class<?>[] types,Object... args)throws Exception {
        var method=RepairProjection.class.getDeclaredMethod(name,types);method.setAccessible(true);
        checkerLimit(()->{
            try { method.invoke(null,args); }
            catch(java.lang.reflect.InvocationTargetException failure) {
                if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
                throw new AssertionError(failure.getCause());
            }catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
        },name);
    }
    private static void fatal(Path root,Path source,String name,boolean collect,
            Supplier<NativeOperationComparison.Result> supplier,String reason,String errorType)throws Exception {
        Path out=Files.createDirectory(root.resolve(name));
        var gate=new BatchParityGate(out,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS,collect);
        var captures=new AtomicInteger();
        stopped(()->gate.requireOperations(source,"left","right",ZERO,ZERO,supplier,
                ()->{captures.incrementAndGet();return new JSONObject().put("retainedSelection",true);}));
        check(captures.get()==1,name+" capture exactly once");
        var incident=read(out.resolve("parity-incident.json"));
        check(incident.getString("reason").equals(reason),name+" reason");
        check(incident.getString("status").equals("STOPPED"),name+" status");
        var retained=read(out.resolve("parity-native-replay.json")).getJSONObject("diagnostic");
        check(retained.getString("errorType").equals(errorType),name+" error provenance");
        check(retained.getJSONObject("diagnostic").getString("failureBoundary")
                .equals("BatchParityGate.requireOperations"),name+" boundary");
        check(gate.operationCoverage().getLong(reason.equals("INVALID_NATIVE_REPLAY")?"invalidReplay":"errors")==1,
                name+" coverage");
        var calls=new AtomicInteger();
        stopped(()->gate.requireOperations(source,"next","right",ZERO,ZERO,()->{calls.incrementAndGet();return null;}));
        check(calls.get()==0,name+" latch precedes next comparison");
        if(collect) {
            var index=Files.readAllLines(out.resolve("replay-discrepancies/index.jsonl"));
            check(index.size()==1,name+" fatal event indexed once");
            var entry=new JSONObject(index.get(0));
            check(read(out.resolve("replay-discrepancies").resolve(entry.getString("incident")))
                    .getString("status").equals("RECORDED_STOPPED"),name+" not continuing");
        }
        if(!Files.exists(source)) {
            check(incident.isNull("sourceSha256")&&incident.has("sourceReadError"),"Missing source not invented");
            check(!read(out.resolve("parity-native-replay.json")).getBoolean("sourceAvailable"),"Missing source noted");
        }
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectory(root);
        Path source=root.resolve("source.als");Files.writeString(source,"sig A {}\n");
        for(boolean collect:new boolean[]{false,true}) {
            fatal(root,source,"runtime-"+collect,collect,()->{throw new IllegalStateException("budget accounting bug");},
                    "NATIVE_RECEIPT_CONSUMER_ERROR",IllegalStateException.class.getName());
            fatal(root,source,"invalid-"+collect,collect,()->{throw new ReplayValidationException("Wrong typed slot");},
                    "INVALID_NATIVE_REPLAY",ReplayValidationException.class.getName());
            fatal(root,root.resolve("missing.als"),"io-"+collect,collect,()->{throw new AssertionError("Read must fail first");},
                    "NATIVE_RECEIPT_CONSUMER_ERROR",java.nio.file.NoSuchFileException.class.getName());
            fatal(root,source,"null-"+collect,collect,()->null,
                    "NATIVE_RECEIPT_CONSUMER_ERROR",IllegalStateException.class.getName());
        }
        var budget=NativeOperationComparison.failure(exhaustion(),null);
        check(budget.status()==NativeOperationComparison.Status.UNCHECKABLE&&budget.reason().equals("REPLAY_BUDGET"),
                "Existing accounting exhaustion stays unresolved, not an invalid proof");
        check(budget.evidence().getString("resourceOrigin").equals("INTERNAL_TRACE_ACCOUNTING"),"Not a machine resource incident");
        try { TraceResources.requireCells(-1,1);throw new AssertionError("Negative accounting accepted"); }
        catch(IllegalArgumentException error) {
            check(NativeOperationComparison.failure(error,null).status()==NativeOperationComparison.Status.ERROR,
                    "Accounting bug is an error");
        }
        check(NativeOperationComparison.failure(new IllegalStateException("group bound bug"),null).status()
                ==NativeOperationComparison.Status.ERROR,"No message-based budget classification");
        projectionLimit("requireProjectedComparisonStep",new Class<?>[]{int[].class},new int[]{0});
        projectionLimit("requireBooleanFlattenBudget",new Class<?>[]{long.class,long.class},Long.MAX_VALUE,0L);
        checkerLimit(()->PhaseReplay.temporalIdentity(0,0,0,n->"x",n->n<130?java.util.List.of(n+1):java.util.List.of()),
                "PhaseReplay temporal tree");
        JSONObject tree=new JSONObject().put("kind","insert").put("cost",0);
        for(int i=0;i<130;i++)tree=new JSONObject().put("kind","select").put("leftAlternative",0).put("rightAlternative",0)
                .put("sourceHead","x").put("targetHead","x").put("sourceSha256","x").put("targetSha256","x").put("selected",tree);
        final var phases=new JSONArray().put(new JSONObject().put("receipt",new JSONObject().put("derivation",tree)));
        checkerLimit(()->PhaseReplay.diagnosticOrder(new JSONObject().put("components",new JSONArray()),phases),
                "PhaseReplay diagnostic order");
        var wrapped=is.fivefivefive.CanDis.metric.NativeRepairTrace.captureFailure(new JSONObject().put("selected",true),exhaustion());
        var wrappedResult=NativeOperationComparison.failure(wrapped,null);
        check(wrappedResult.status()==NativeOperationComparison.Status.UNCHECKABLE
                &&wrappedResult.evidence().getJSONObject("retainedNumericSelection").getBoolean("selected"),"Known capture wrapper retains limit and selection");
        Path out=Files.createDirectory(root.resolve("ordinary-collection"));
        var gate=new BatchParityGate(out,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS,true);
        check(gate.requireOperations(source,"left","right",ZERO,ZERO,()->budget).status()
                ==NativeOperationComparison.Status.UNCHECKABLE,"Refusal retained");
        gate.checkRunning();
        var pair=NativeOperationComparisonTest.simple();
        var mismatch=NativeOperationComparisonTest.differing(pair,NativeOperationComparisonTest.compare(pair));
        check(mismatch.status()==NativeOperationComparison.Status.MISMATCH,"Real checked action mismatch fixture");
        var report=mismatch.evidence().getJSONObject("diagnostic");
        int[] fast=costs(report.getJSONObject("fast")),certified=costs(report.getJSONObject("certified"));
        check(gate.requireOperations(source,"left","right",fast,certified,()->mismatch).status()
                ==NativeOperationComparison.Status.MISMATCH,"Ordinary mismatch retained");
        gate.requireParity(source,"left","right",new int[]{1,0,0,1},new int[]{2,0,0,2});
        gate.checkRunning();
        check(gate.discrepancyCoverage().getLong("events")==3,"All three ordinary findings indexed");
        check(!Files.exists(out.resolve("parity-incident.json")),"Ordinary findings do not latch fatal stop");
        for(String line:Files.readAllLines(out.resolve("replay-discrepancies/index.jsonl")))
            check(read(out.resolve("replay-discrepancies").resolve(new JSONObject(line).getString("incident")))
                    .getString("status").equals("RECORDED_CONTINUING"),"Continuation explicit");
        Files.writeString(root.resolve("checks.json"),new JSONObject().put("checks",checks).put("status","PASS").toString(2));
        System.out.println("BatchOperationFailureTest: "+checks+" checks passed");
    }
    private static int[] costs(JSONObject arm) {
        return new int[]{arm.getInt("distance"),arm.getInt("temporalDistance"),arm.getInt("quantifierDistance"),arm.getInt("matrixDistance")};
    }
}
