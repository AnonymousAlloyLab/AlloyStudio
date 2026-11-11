package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

/** Postmortem only. Exit zero means a diagnostic file was written, never parity approval. */
public final class DistanceReplayDiagnostics {
    public static final String SCHEMA = "acgn-matrix-replay-v1";
    private DistanceReplayDiagnostics() { }

    public static JSONObject capture(Canonical.Prepared left, Canonical.Prepared right) {
        return capture(left, right, () -> CanonicalRepairTraces.certified(
                CanonicalAlloyPipeline.prepare(left),CanonicalAlloyPipeline.prepare(right)).json());
    }

    static JSONObject capture(Canonical.Prepared left, Canonical.Prepared right,
            CanonicalAlloyPipeline.Prepared leftExact, CanonicalAlloyPipeline.Prepared rightExact) {
        return capture(left, right, () -> CanonicalRepairTraces.certified(leftExact,rightExact).json());
    }

    private static JSONObject capture(Canonical.Prepared left, Canonical.Prepared right,
            Supplier<JSONObject> certified) {
        JSONObject report = envelope();
        report.put("fast", arm(() -> Canonical.evaluateWithTrace(left,right).json()));
        report.put("certified", arm(certified));
        return report;
    }

    /** Diagnostic capture of the exact records that supplied the scan costs; no optimization. */
    public static JSONObject captureTraces(is.fivefivefive.CanDis.metric.NativeRepairTrace fast,
            is.fivefivefive.CanDis.metric.NativeRepairTrace certified) {
        JSONObject a=fast.json(),b=certified.json();fast.replay(a);certified.replay(b);
        return envelope().put("fast",a.put("diagnosticStatus","MATRIX_REPLAY_CHECKED"))
                .put("certified",b.put("diagnosticStatus","MATRIX_REPLAY_CHECKED"))
                .put("sameSelectedRecordsAsNumericEvaluation",true);
    }

    private static JSONObject arm(Supplier<JSONObject> supplier) {
        try { return supplier.get().put("diagnosticStatus", "MATRIX_REPLAY_CHECKED"); }
        catch (RuntimeException ex) {
            var failure=new JSONObject().put("diagnosticStatus", "BLOCKED").put("complete", false)
                    .put("matrixReplayChecked", false).put("errorType", ex.getClass().getName())
                    .put("error", String.valueOf(ex.getMessage()));
            if(ex instanceof is.fivefivefive.CanDis.metric.NativeRepairTrace.CaptureFailure capture)
                failure.put("retainedNumericSelection",capture.selectedReceipt()).put("nativeTraceExtractionFailed",true);
            if(ex instanceof is.fivefivefive.CanDis.metric.NativeRepairTrace.ReplayFailure replay)
                failure.put("firstDifferingOperation",replay.operation()).put("payloadPath",replay.payloadPath());
            return failure;
        }
    }

    private static JSONObject envelope() {
        return new JSONObject().put("schema", SCHEMA).put("status", "BLOCKED")
                .put("nativeTraceSchema","acgn-distance-derived-trace-v1")
                .put("supplementalSliceVersion","fixed-order-declaration-and-temporal-edits-v2")
                .put("complete", false).put("parityAuthority", false)
                .put("temporalReplayChecked", false).put("quantifierReplayChecked", false)
                .put("choiceAdmissionIndependentlyChecked", false)
                .put("sourceCertificateWireReplayChecked", false)
                .put("javaLeanCorrespondenceChecked", false).put("publicSequenceLinked", false)
                .put("blockingReasons", new JSONArray().put("PUBLIC_TEMPORAL_PLAN_NOT_LINKED")
                        .put("PUBLIC_QUANTIFIER_PLAN_NOT_LINKED").put("ORDERING_ADMISSION_NOT_INDEPENDENTLY_REPLAYED")
                        .put("CHOICE_ADMISSION_NOT_INDEPENDENTLY_REPLAYED")
                        .put("SOURCE_CERTIFICATE_WIRE_REPLAY_NOT_PERFORMED")
                        .put("JAVA_LEAN_CORRESPONDENCE_NOT_PROVED"))
                .put("scope", "Executable native metric-representation programs from retained numeric decisions; construction derivations and cross-arm diagnostics remain separate; no public certification")
                .put("trusted", new JSONArray().put("native preparation and choice search")
                        .put("native head-cost and subtree-size rules")
                        .put("Java replay checker, JSON library, SHA-256, JVM and OS"));
    }

    /** Args: SOURCE LEFT_PREDICATE RIGHT_PREDICATE FRESH_OUTPUT_JSON. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "Usage: DistanceReplayDiagnostics SOURCE LEFT RIGHT OUTPUT");
        Path source = Path.of(args[0]), output = Path.of(args[3]);
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists: " + output);
        JSONObject report;
        byte[] original = Files.readAllBytes(source);
        try {
            var module = AlloyUtil.compileAlloyModule(source.toString());
            if (module == null) throw new IllegalArgumentException("Source failed to parse");
            report = capture(prepare(module, args[1]), prepare(module, args[2]));
        } catch (RuntimeException ex) {
            report = envelope().put("preparationError", ex.toString());
        }
        if (!java.util.Arrays.equals(original, Files.readAllBytes(source)))
            throw new IllegalStateException("Source changed during diagnostic");
        report.put("source", source.toAbsolutePath().toString()).put("leftPredicate", args[1])
                .put("rightPredicate", args[2]).put("sourceSha256", HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(original)));
        Files.writeString(output, report.toString(2) + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("MATRIX_REPLAY_REPORT " + output.toAbsolutePath() + " status=BLOCKED complete=false");
    }

    private static Canonical.Prepared prepare(edu.mit.csail.sdg.parser.CompModule module, String name) {
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of(name), module);
        visitor.visit(new ModelUnit(null, module), null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
}
