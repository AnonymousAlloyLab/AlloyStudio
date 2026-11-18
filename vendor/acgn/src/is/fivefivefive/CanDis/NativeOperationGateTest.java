package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public final class NativeOperationGateTest {
    private static int checks;
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void stopped(Runnable action){checks++;try{action.run();}catch(BatchParityGate.Violation expected){return;}throw new AssertionError("Gate continued");}
    private static int[] costs(JSONObject arm){return new int[]{arm.getInt("distance"),arm.getInt("temporalDistance"),arm.getInt("quantifierDistance"),arm.getInt("matrixDistance")};}
    public static void main(String[] args)throws Exception {
        Path root=args.length==0?Files.createTempDirectory("native-operation-gate-"):Path.of(args[0]);Files.createDirectories(root);
        Path source=root.resolve("pair.als");Files.writeString(source,"sig A { r,s:set A } pred inv { some r and some s } pred invc { no r and no s }",StandardOpenOption.CREATE_NEW);
        var p=NativeOperationComparisonTest.simple();var captured=NativeOperationComparisonTest.compare(p);
        var mismatch=NativeOperationComparisonTest.differing(p,captured);
        check(mismatch.status()==NativeOperationComparison.Status.MISMATCH,"actual replayed differing sequence");
        var report=mismatch.evidence().getJSONObject("diagnostic");int[] fast=costs(report.getJSONObject("fast")),cert=costs(report.getJSONObject("certified"));
        Path stop=Files.createDirectory(root.resolve("stop"));var gate=new BatchParityGate(stop,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS);
        gate.requireParity(source,"inv","invc",fast,cert);
        stopped(()->gate.requireOperations(source,"inv","invc",fast,cert,()->mismatch));
        check(gate.operationCoverage().getLong("mismatches")==1,"mismatch coverage");
        var incident=new JSONObject(Files.readString(stop.resolve("parity-incident.json")));
        check(incident.getString("reason").equals("NATIVE_OPERATION_MISMATCH") && incident.getString("sourceText").equals(Files.readString(source)),"first original source retained");
        var nativeReport=new JSONObject(Files.readString(stop.resolve("parity-native-replay.json"))).getJSONObject("diagnostic");
        check(nativeReport.has("firstDifference") && nativeReport.getBoolean("scanCostBindingChecked"),"exact scan costs and first differing action retained");
        stopped(gate::checkRunning);byte[] original=Files.readAllBytes(stop.resolve("parity-native-replay.json"));
        stopped(()->gate.requireOperations(source,"later","invc",fast,cert,()->mismatch));
        check(java.util.Arrays.equals(original,Files.readAllBytes(stop.resolve("parity-native-replay.json"))),"no overwrite");
        try{new BatchParityGate(stop,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS);throw new AssertionError("Existing evidence accepted");}catch(IllegalStateException expected){checks++;}
        Path drift=Files.createDirectory(root.resolve("drift"));var driftGate=new BatchParityGate(drift,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS);
        int[] driftCosts=fast.clone();driftCosts[0]++;driftCosts[3]++;
        stopped(()->driftGate.requireOperations(source,"inv","invc",driftCosts,driftCosts,()->mismatch));
        check(new JSONObject(Files.readString(drift.resolve("parity-incident.json"))).getString("reason").equals("INVALID_NATIVE_REPLAY"),"both-arms drift is invalid");
        Path concurrent=Files.createDirectory(root.resolve("concurrent"));var shared=new BatchParityGate(concurrent,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS);
        var start=new CountDownLatch(1);var failures=new AtomicInteger();Thread[] workers=new Thread[4];
        for(int i=0;i<workers.length;i++){workers[i]=new Thread(()->{try{start.await();shared.requireOperations(source,"inv","invc",fast,cert,()->mismatch);}catch(BatchParityGate.Violation expected){failures.incrementAndGet();}catch(InterruptedException e){Thread.currentThread().interrupt();}});workers[i].start();}
        start.countDown();for(var worker:workers)worker.join(5000);
        check(failures.get()==4 && shared.operationCoverage().getLong("attempted")==1,"concurrent first latch counts one fatal outcome");
        Path naturalSource=Path.of("classified-data/classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12.als");
        var natural=NativeOperationComparisonTest.pair(Files.readString(naturalSource),"inv12","inv12c");
        var matched=NativeOperationComparisonTest.compare(natural);check(matched.status()==NativeOperationComparison.Status.MATCHED,"native checked match");
        var mr=matched.evidence().getJSONObject("diagnostic");int[] mc=costs(mr.getJSONObject("fast"));
        var pass=new BatchParityGate(Files.createDirectory(root.resolve("continue")),BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS);
        pass.requireOperations(naturalSource,"inv12","inv12c",mc,mc,()->matched);pass.checkRunning();
        var missing=new JSONObject(mr.toString());missing.getJSONObject("fast").remove("declarationSlice");
        var unchecked=NativeOperationComparison.compareCaptured(natural.left(),natural.right(),missing);
        check(unchecked.status()==NativeOperationComparison.Status.UNCHECKABLE,"missing support explicitly unchecked");
        stopped(()->pass.requireOperations(naturalSource,"inv12","inv12c",mc,mc,()->unchecked,()->mr));
        var coverage=pass.operationCoverage();check(coverage.getLong("attempted")==2 && coverage.getLong("matched")==1
                && coverage.getLong("unchecked")==1 && coverage.getJSONArray("examples").length()==1,"missing mapping stops without skipping");
        check(new JSONObject(Files.readString(root.resolve("continue/parity-incident.json"))).getString("reason")
                .equals("TRACE_CORRESPONDENCE_DEFECT"),"diagnostic defect not unsupported predicate");
        check(new JSONObject(Files.readString(root.resolve("continue/parity-native-replay.json")))
                .getJSONObject("diagnostic").getJSONObject("selectedNativeTraces").similar(mr),"native traces retained before mapping");
        stopped(pass::checkRunning);
        var extraction=new BatchParityGate(Files.createDirectory(root.resolve("extraction")),BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS);
        stopped(()->extraction.captureTrace(source,"inv","invc",()->{
            throw is.fivefivefive.CanDis.metric.NativeRepairTrace.captureFailure(new JSONObject().put("distance",7),
                    new IllegalArgumentException("broken extraction"));
        },()->new JSONObject().put("retainedPrevious",true)));
        stopped(extraction::checkRunning);
        var failed=new JSONObject(Files.readString(root.resolve("extraction/parity-incident.json")));
        check(failed.getString("reason").equals("TRACE_EXTRACTION_FAILED") && failed.isNull("fast")
                && failed.isNull("certified"),"extraction stops without inventing distances");
        var failedTrace=new JSONObject(Files.readString(root.resolve("extraction/parity-native-replay.json"))).getJSONObject("diagnostic");
        check(failedTrace.getJSONObject("retainedNumericSelection").getInt("distance")==7
                && failedTrace.getJSONObject("previousNativeTrace").getBoolean("retainedPrevious"),"failed selection and prior arm retained");
        check(coverage.getString("certificationStatus").equals("BLOCKED"),"no certification grant");
        Path collected=Files.createDirectory(root.resolve("collected"));
        var all=new BatchParityGate(collected,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS,true);
        all.requireParity(source,"inv","invc",fast,driftCosts);
        all.requireOperations(source,"inv","invc",fast,cert,()->mismatch,()->report);
        all.requireOperations(naturalSource,"inv12","inv12c",mc,mc,()->unchecked,()->mr);
        all.requireOperations(naturalSource,"inv12","inv12c",mc,mc,()->matched);
        all.checkRunning();
        check(all.discrepancyCoverage().getLong("events")==3,"ordinary discrepancies collected without latching");
        check(all.operationCoverage().getLong("attempted")==3 && all.operationCoverage().getLong("matched")==1
                && all.operationCoverage().getLong("mismatches")==1 && all.operationCoverage().getLong("unchecked")==1
                && all.operationCoverage().getLong("invalidReplay")==0,"collecting never promotes nonmatches");
        Path findings=collected.resolve("replay-discrepancies");
        var recorded=new JSONObject(Files.readString(findings.resolve("00000002/incident.json")));
        check(recorded.getString("reason").equals("NATIVE_OPERATION_MISMATCH")
                && recorded.getString("sourceText").equals(Files.readString(source))
                && !recorded.getBoolean("publicationAllowed"),"collected exact source and diagnostic boundary");
        var collectedNative=new JSONObject(Files.readString(findings.resolve("00000002/native-replay.json")))
                .getJSONObject("diagnostic");
        check(collectedNative.has("firstDifference") && collectedNative.getBoolean("scanCostBindingChecked")
                && collectedNative.has("selectedNativeTraces"),"collected first action and actual selected receipts");
        // Fatal cases have fresh gates: invalid/internal failures are never collect-and-continue.
        Path collectedDrift=Files.createDirectory(root.resolve("collected-drift"));
        var fatalDrift=new BatchParityGate(collectedDrift,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS,true);
        stopped(()->fatalDrift.requireOperations(source,"inv","invc",driftCosts,driftCosts,()->mismatch));
        stopped(fatalDrift::checkRunning);
        check(fatalDrift.operationCoverage().getLong("invalidReplay")==1
                && fatalDrift.discrepancyCoverage().getLong("events")==1,"invalid drift retained and latched");
        Path collectedExtraction=Files.createDirectory(root.resolve("collected-extraction"));
        var fatalExtraction=new BatchParityGate(collectedExtraction,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS,true);
        stopped(()->fatalExtraction.captureTrace(source,"inv","invc",()->{throw is.fivefivefive.CanDis.metric.NativeRepairTrace.captureFailure(
                new JSONObject().put("distance",7),new IllegalArgumentException("broken extraction"));},()->mr));
        stopped(fatalExtraction::checkRunning);
        var extractionEvidence=new JSONObject(Files.readString(collectedExtraction.resolve("replay-discrepancies/00000001/native-replay.json")))
                .getJSONObject("diagnostic");
        check(extractionEvidence.getJSONObject("retainedNumericSelection").getInt("distance")==7
                && extractionEvidence.has("previousNativeTrace"),"collect failed selection without pretending replayed");
        Thread[] collectors=new Thread[4];
        for(int i=0;i<collectors.length;i++) {
            collectors[i]=new Thread(()->all.requireOperations(source,"inv","invc",fast,cert,()->mismatch));
            collectors[i].start();
        }
        for(var collector:collectors)collector.join(5000);
        check(all.discrepancyCoverage().getLong("events")==7
                && Files.readAllLines(findings.resolve("index.jsonl")).size()==7,"concurrent findings have distinct retained records");
        all.checkRunning();
        Path collectedFile=Files.createDirectory(root.resolve("collected-file"));
        var fatalFile=new BatchParityGate(collectedFile,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS,true);
        stopped(()->fatalFile.recordFileFailure(source,null,null,"original parse failure"));
        stopped(fatalFile::checkRunning);
        var laterCalls=new AtomicInteger();
        stopped(()->fatalFile.requireOperations(source,"inv","invc",fast,cert,()->{laterCalls.incrementAndGet();return mismatch;}));
        check(laterCalls.get()==0,"file failure prevents next comparison");
        var unnamed=new JSONObject(Files.readString(collectedFile.resolve("replay-discrepancies/00000001/incident.json")));
        var unnamedEvidence=new JSONObject(Files.readString(collectedFile.resolve("replay-discrepancies/00000001/native-replay.json")));
        check(unnamed.isNull("leftPredicate") && unnamed.isNull("rightPredicate")
                && unnamedEvidence.isNull("leftPredicate")
                && unnamedEvidence.getJSONObject("diagnostic").getString("error").equals("original parse failure"),
                "null predicate names retain the original file failure");
        check(unnamed.getString("status").equals("RECORDED_STOPPED")
                && fatalFile.discrepancyCoverage().getLong("events")==1
                && Files.readAllLines(collectedFile.resolve("replay-discrepancies/index.jsonl")).size()==1,"unnamed fatal failure indexed once");
        var mainFile=new JSONObject(Files.readString(collectedFile.resolve("parity-incident.json")));
        var mainFileEvidence=new JSONObject(Files.readString(collectedFile.resolve("parity-native-replay.json")));
        check(mainFile.isNull("leftPredicate")&&mainFile.isNull("rightPredicate")
                &&mainFileEvidence.isNull("leftPredicate")&&mainFileEvidence.isNull("rightPredicate")
                &&mainFileEvidence.getJSONObject("diagnostic").getString("error").equals("original parse failure"),
                "main fatal evidence preserves unknown identity and exact error");
        try{new BatchParityGate(collected,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS,true);
            throw new AssertionError("Collected findings overwritten");}catch(IllegalStateException expected){checks++;}
        try{new BatchParityGate(root,BatchParityGate.Mode.STRICT_CERTIFIED,true);
            throw new AssertionError("Collecting enabled strict certification");}catch(IllegalArgumentException expected){checks++;}
        Path unavailable=Files.createDirectory(root.resolve("unavailable"));
        var failedWriter=new BatchParityGate(unavailable,BatchParityGate.Mode.DIAGNOSTIC_OPERATIONS,true);
        Files.writeString(unavailable.resolve("replay-discrepancies"),"not a directory");
        stopped(()->failedWriter.requireParity(source,"inv","invc",fast,driftCosts));
        stopped(failedWriter::checkRunning);
        Files.writeString(root.resolve("coverage.json"),coverage.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("NativeOperationGateTest: "+checks+" checks passed");
    }
}
