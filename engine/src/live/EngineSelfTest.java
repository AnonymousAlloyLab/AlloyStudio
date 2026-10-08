package live;

import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.LiveTrace;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.io.OutputStream;
import java.io.PrintStream;

/** Finite adapter regression fixtures; no oracle bytes are logged on failure. */
public final class EngineSelfTest {
    private static int checks;
    private static final String ENV = "module fixture\nsig A { r: set A }\n";

    public static void main(String[] args) {
        PrintStream report = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        JSONObject identical = compare("some A", "some A");
        check(identical.getInt("distance") == 0, "identity distance");
        check(identical.getJSONArray("operations").length() == 0, "identity operations");
        check(compare("no A", "some A").getInt("distance") == 1, "known unary edit distance");
        check(compare("all x: A | x in A", "all y: A | y in A").getInt("distance") == 0,
                "alpha renaming");
        check(compare("some A and no r", "no r and some A").getInt("distance") == 0,
                "conjunction commutativity");
        check(compare("some A and some A", "some A").getInt("distance") == 0,
                "conjunction idempotence");
        check(compare("no A", "some A").getInt("distance")
                        == compare("some A", "no A").getInt("distance"), "distance symmetry");
        check(compare("all x: A | some x.r", "some x: A | some x.r")
                .getJSONObject("operationSummary").getInt("modify") == 1, "quantifier modification");
        check(compare("some A", "all x: A | some x.r")
                .getJSONObject("operationSummary").getInt("insert") > 0, "insert operations");
        check(compare("all x: A | some x.r", "some A")
                .getJSONObject("operationSummary").getInt("delete") > 0, "delete operations");
        JSONObject temporalFallback = compare("always eventually some A", "eventually some A");
        check(temporalFallback.getJSONObject("trace").getBoolean("hasAggregates"),
                "temporal trace discrepancy is explicit");
        String callEnvironment = ENV + "pred p { some A }\npred q { no A }\n";
        JSONObject callFallback = evaluate(callEnvironment + "pred target { p }\n",
                callEnvironment + "pred target { q }\n");
        check(callFallback.getInt("distance") == 1, "CALL identity edit distance");
        check(!callFallback.getJSONObject("trace").getBoolean("hasAggregates"),
                "CALL matrix trace is reconstructed");
        check(callFallback.getJSONArray("operations").getJSONObject(0).getString("kind").equals("replace"),
                "CALL identity trace includes real replacement");
        check(callFallback.getJSONArray("operations").getJSONObject(0).getString("sourceTerm").contains("p"),
                "CALL replacement identifies learner call");
        check(LiveTrace.replayRejectsTamperedReplacement(LiveFeedback.prepare(source("no A"), "target"),
                LiveFeedback.prepare(source("some A"), "target")), "private replay rejects omitted and corrupted replacement");
        check(LiveTrace.assignmentWitnessesPass(), "72 exhaustive small assignment fixtures");
        String prefix = "some A and no A.r and one A.r.r and ";
        JSONObject unordered = compare(prefix + "lone A.r.r.r", prefix + "some A.r.r.r");
        check(unordered.getInt("distance") == 1 && unordered.getJSONArray("operations").length() == 1,
                "unordered exact alignment replaces sixteen upstream hints with one operation");
        check(unordered.getJSONArray("operations").getJSONObject(0).getString("replacementOperator").equals("some"),
                "safe replacement operator exposed");

        String[] bodies = { "some A", "no A", "all x: A | some x.r", "some x: A | no x.r",
                "always some A", "eventually some A", "always eventually some A",
                "A in A", "some A and no r", "some r and no A" };
        for (int i = 0; i < bodies.length; i++) {
            JSONObject result = compare(bodies[i], bodies[(i + 3) % bodies.length]);
            check(result.getInt("distance") >= 0, "nonnegative metric");
            check(result.getInt("distance") == Canonical.distance(
                    LiveFeedback.prepare(source(bodies[i]), "target"),
                    LiveFeedback.prepare(source(bodies[(i + 3) % bodies.length]), "target")),
                    "direct ACGN metric agreement");
            check(result.getJSONArray("canonicalForm").toString().equals(
                    new JSONArray(Canonical.irTemporalFol(LiveFeedback.prepare(source(bodies[i]), "target"))).toString()),
                    "learner-only canonical form");
        }

        JSONObject wrongSyntax = evaluate(source("some ("), source("some A"));
        check(wrongSyntax.getString("status").equals("invalid"), "syntax error status");
        check(wrongSyntax.getJSONArray("diagnostics").getJSONObject(0).has("line"), "syntax error position");
        JSONObject wrongType = evaluate(source("some A and A"), source("some A"));
        check(wrongType.getString("status").equals("invalid"), "type error status");
        JSONObject unknownName = evaluate(source("some REFERENCE_SECRET"), source("some A"));
        check(!unknownName.toString().contains("REFERENCE_SECRET"), "learner diagnostic payload suppression");
        JSONObject badReference = evaluate(source("some A"), "REFERENCE_SECRET invalid source");
        check(badReference.getString("status").equals("engine_error"), "reference failure status");
        check(!badReference.toString().contains("REFERENCE_SECRET"), "reference failure confidentiality");
        JSONObject privateReference = evaluate(source("some A"),
                "module fixture\nsig A {}\nsig REFERENCE_SECRET {}\npred target { some REFERENCE_SECRET }\n");
        check(privateReference.getString("status").equals("ok"), "private symbol comparison succeeds");
        check(!privateReference.toString().contains("REFERENCE_SECRET"), "reference label confidentiality");

        JSONArray redacted = LiveFeedback.redactedOperations(List.of(
                "REFERENCE_SECRET normal form.matrix.child[4]: insert CONSTANT(REFERENCE_SECRET)",
                "REFERENCE_SECRET normal form.quantifier[3]: modify x -> REFERENCE_SECRET",
                "temporal.child[2]: replace NONE -> REFERENCE_SECRET"));
        check(redacted.length() == 3, "redaction retains operation kinds");
        check(!redacted.toString().contains("REFERENCE_SECRET"), "path and payload redaction");
        check(redacted.getJSONObject(0).getString("path").equals("normalForm[0].matrix.child[4]"),
                "safe structural path");
        check(LiveFeedback.evaluate(new JSONObject()).getString("status").equals("invalid_request"),
                "missing fields");
        check(LiveFeedback.evaluate(new JSONObject().put("studentSource", source("some A"))
                .put("oracleSource", source("some A")).put("predicate", "target;invalid"))
                .getString("status").equals("invalid_request"), "predicate validation");
        testNearestCorrectPools();
        testRawAstMode();
        testBehaviorRewards();
        testBehaviorPools();
        report.println("EngineSelfTest passed (" + checks + " checks)");
    }

    private static void testBehaviorRewards() {
        check(BehaviorReward.millis(100, 100, 100, 100, 1, false) == 999,
                "10000/10001 counterexample cannot round to full reward");
        check(BehaviorReward.millis(100, 100, 100, 100, 0, false) == 999,
                "incomplete semantic evidence cannot receive full reward");
        check(BehaviorReward.millis(100, 100, 100, 100, 0, true) == 1000,
                "complete bounded agreement receives full reward");
        check(BehaviorReward.millis(4, 2, 4, 3, 0, false) == 375,
                "integer score preserves ordinary ACGN ratio");
        check(BehaviorReward.millis(8, 1, 1, 1, 0, false) == 125,
                "integer score preserves exact thousandths");
        check(BehaviorReward.millis(16, 1, 1, 1, 0, false) == 63,
                "integer score rounds midpoint half upward");
        check(BehaviorReward.millis(1, 0, 1, 0, 0, false) == 0,
                "zero classification score remains zero");
        for (int p = 1; p <= 100; p++) {
            check(BehaviorReward.millis(p, p, 100, 100, 1, false) < 1000,
                    "counterexample full-score exclusion over every pool size");
        }
        BehaviorFeedback.resetPools();
        JSONObject request = new JSONObject().put("studentSource", source("some A"))
                .put("oracleSource", source("some A")).put("studentBody", "some A").put("predicate", "target");
        JSONObject first = BehaviorFeedback.evaluate(request);
        check(first.getString("status").equals("ok") && first.getDouble("score") == 1,
                "Alloy complete bounded agreement full score");
        long[] before = BehaviorFeedback.poolState();
        JSONObject repeated = BehaviorFeedback.evaluate(request);
        long[] after = BehaviorFeedback.poolState();
        check(repeated.getDouble("score") == 1 && after[0] <= 100 && after[1] <= 100,
                "repeated edits retain bounded compatible Alloy signature state");
        check(after[2] > before[2], "warm fresh evaluation advances positive enumeration");
        JSONObject wrong = BehaviorFeedback.evaluate(new JSONObject(request.toString())
                .put("studentSource", source("no A")).put("studentBody", "no A"));
        check(wrong.getString("status").equals("ok") && wrong.getDouble("score") < 1,
                "actual Alloy counterexamples exclude full reward");
        check(wrong.getJSONObject("sampling").getInt("positiveAccepted")
                < wrong.getJSONObject("sampling").getInt("positiveTested"),
                "fresh counterexample witnesses participate in final counts");
        JSONObject switched = BehaviorFeedback.evaluate(new JSONObject(request.toString())
                .put("oracleSource", source("no A")).put("studentSource", source("no A")).put("studentBody", "no A"));
        check(switched.getString("status").equals("ok") && switched.getDouble("score") == 1,
                "exact oracle context switch releases prior classifications");
        check(BehaviorFeedback.poolState()[0] == 1, "context switch starts fresh positive oracle pool");
        BehaviorFeedback.resetPools();
    }

    private static void testBehaviorPools() {
        BehaviorPool<Integer> pool = new BehaviorPool<>(2);
        pool.admit("a", 1); pool.admit("b", 2);
        check(pool.size() == 2, "LFU capacity starts bounded");
        long epoch = pool.epoch();
        pool.admit("a", 99);
        check(pool.size() == 2 && pool.epoch() == epoch && pool.snapshot().get(0).value == 1,
                "duplicate identity does not replace or touch retained witness");
        pool.mismatch(pool.snapshot().get(0));
        pool.admit("c", 3);
        check(pool.snapshot().get(0).identity.equals("a") && pool.snapshot().get(1).identity.equals("c"),
                "LFU evicts least frequently defect-detecting witness");
        BehaviorPool<Integer> ties = new BehaviorPool<>(2);
        ties.admit("a", 1); ties.admit("b", 2); ties.admit("c", 3);
        check(ties.snapshot().get(0).identity.equals("b") && ties.snapshot().get(1).identity.equals("c"),
                "LFU oldest insertion tie breaking");
        pool.mismatch(pool.snapshot().get(1));
        pool.admit("d", 4);
        check(pool.snapshot().get(0).identity.equals("c") && pool.snapshot().get(1).identity.equals("d"),
                "frequency updates preserve insertion order for ties");
        try {
            BehaviorPool.Entry<Integer> entry = pool.snapshot().get(0);
            var frequency = BehaviorPool.Entry.class.getDeclaredField("frequency");
            frequency.setAccessible(true); frequency.setInt(entry, Integer.MAX_VALUE);
            pool.mismatch(entry);
            check(entry.frequency() == Integer.MAX_VALUE, "LFU frequency saturates without wrapping");
            var version = BehaviorPool.class.getDeclaredField("epoch");
            version.setAccessible(true); version.setLong(pool, Long.MAX_VALUE);
            pool.admit("e", 5);
            check(pool.epoch() == Long.MAX_VALUE, "LFU diagnostic epoch saturates without wrapping");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("LFU saturation fixture failed", error);
        }
        BehaviorPool<Integer> bounded = new BehaviorPool<>(100);
        boolean alwaysBounded = true;
        for (int i = 0; i < 1000; i++) {
            bounded.admit("w" + i, i);
            alwaysBounded &= bounded.size() <= 100;
        }
        check(alwaysBounded && bounded.size() == 100 && bounded.snapshot().get(0).value == 900,
                "continuous LFU updates retain only finite entries and identities");
    }

    private static void testRawAstMode() {
        JSONObject ast = LiveFeedback.evaluate(new JSONObject().put("metric", "ast")
                .put("studentSource", source("no A")).put("oracleSource", source("some A")).put("predicate", "target"));
        check(ast.getString("metric").equals("acgn-raw-ast-zhang-shasha-distance") && ast.getInt("distance") == 1,
                "raw AST authoritative one-node distance");
        check(ast.getJSONObject("trace").getBoolean("astReplayVerified") && ast.getJSONArray("operations").length() == 1
                && ast.getJSONArray("operations").getJSONObject(0).getInt("cost") == 1, "raw AST node edit replay");
        check(ast.getJSONArray("canonicalForm").isEmpty() && ast.getInt("astSize") > 0,
                "raw AST mode does not substitute canonical normalization");
        JSONObject nearest = LiveFeedback.evaluate(poolRequest("some A and no r",
                List.of("no r and some A", "some A and no r")).put("metric", "ast"));
        check(nearest.getInt("distance") == 0 && nearest.getJSONObject("comparison").getInt("evaluatedCandidates") == 2,
                "raw AST complete nearest correct pool");
        JSONObject invalid = LiveFeedback.evaluate(poolRequest("some A", List.of("some A", "some PRIVATE_AST_NAME")).put("metric", "ast"));
        check(invalid.getString("status").equals("engine_error") && !invalid.has("distance")
                && !invalid.toString().contains("PRIVATE_AST_NAME"), "raw AST rejects incomplete private pool");
        check(LiveFeedback.evaluate(poolRequest("some A", List.of("some A")).put("metric", "unknown"))
                .getString("status").equals("invalid_request"), "unknown metric rejected");
    }

    private static void testNearestCorrectPools() {
        JSONObject nearest = LiveFeedback.evaluate(poolRequest("no A", List.of("all x:A | some x.r", "some A")));
        check(nearest.getString("status").equals("ok"), "complete pool comparison succeeds");
        check(nearest.getInt("distance") == 1, "nearest candidate wins over first reference");
        check(nearest.getJSONArray("operations").length() == 1, "only nearest candidate is traced");
        check(nearest.getJSONArray("operations").getJSONObject(0).getString("replacementOperator").equals("some"),
                "winning candidate replacement operator");
        JSONObject metadata = nearest.getJSONObject("comparison");
        check(metadata.getString("strategy").equals("nearest-known-correct"), "pool strategy label");
        check(metadata.getInt("poolSize") == 2 && metadata.getInt("evaluatedCandidates") == 2,
                "every pool candidate evaluated");
        check(metadata.getBoolean("complete"), "complete pool result");
        check(metadata.length() == 4, "selected reference identity not exposed");
        String correctVariant = "all x:A | x not in x.r";
        JSONObject oracleOnly = evaluate(source(correctVariant), source("no iden & r"));
        check(oracleOnly.getInt("distance") > 0, "correct syntactic variant differs from fixed oracle under metric");
        JSONObject variant = LiveFeedback.evaluate(poolRequest(correctVariant, List.of("no iden & r", correctVariant)));
        check(variant.getInt("distance") == 0 && variant.getJSONArray("operations").length() == 0,
                "admitted correct formulation matches itself without oracle-only penalty");
        JSONObject firstTie = LiveFeedback.evaluate(poolRequest("some A", List.of("no A", "one A")));
        JSONObject reversedTie = LiveFeedback.evaluate(poolRequest("some A", List.of("one A", "no A")));
        check(firstTie.getJSONArray("operations").getJSONObject(0).getString("replacementOperator").equals("no"),
                "first input wins an equal-distance tie");
        check(reversedTie.getJSONArray("operations").getJSONObject(0).getString("replacementOperator").equals("one"),
                "tie order is explicit and deterministic");
        JSONObject invalidAfterZero = LiveFeedback.evaluate(poolRequest("some A", List.of("some A", "some PRIVATE_POOL_SENTINEL")));
        check(invalidAfterZero.getString("status").equals("engine_error"), "invalid candidate after zero invalidates entire pool");
        check(!invalidAfterZero.has("distance") && !invalidAfterZero.has("comparison"), "partial minimum is never published");
        check(!invalidAfterZero.toString().contains("PRIVATE_POOL_SENTINEL"), "invalid pool diagnostics remain private");
        check(LiveFeedback.evaluate(poolRequest("some A", List.of())).getString("status").equals("invalid_request"), "empty pool rejected");
        check(LiveFeedback.evaluate(poolRequest("some A", List.of(" "))).getString("status").equals("invalid_request"), "blank reference rejected");
        JSONObject mixed = poolRequest("some A", List.of("some A")).put("oracleSource", source("some A"));
        check(LiveFeedback.evaluate(mixed).getString("status").equals("invalid_request"), "ambiguous oracle fallback input rejected");
        JSONObject malformed = poolRequest("some A", List.of("some A")).put("referenceBodies", new JSONArray().put(42));
        check(LiveFeedback.evaluate(malformed).getString("status").equals("invalid_request"), "nonstring reference rejected");
        JSONObject duplicate = LiveFeedback.evaluate(poolRequest("some A", List.of("some A", "some A", "no A")));
        check(duplicate.getJSONObject("comparison").getInt("evaluatedCandidates") == 3, "zero-distance and duplicate candidates do not cut off enumeration");
    }

    private static JSONObject poolRequest(String learner, List<String> references) {
        return new JSONObject().put("studentSource", source(learner)).put("predicate", "target")
                .put("referenceBodies", new JSONArray(references)).put("referencePrefix", ENV + "pred target {\n")
                .put("referenceSuffix", "\n}\nrun target for 3\n");
    }

    private static JSONObject compare(String student, String oracle) {
        JSONObject result = evaluate(source(student), source(oracle));
        check(result.getString("status").equals("ok"), "comparison completed");
        JSONObject breakdown = result.getJSONObject("breakdown");
        check(result.getInt("distance") == breakdown.getInt("temporal") + breakdown.getInt("quantifier")
                + breakdown.getInt("matrix"), "metric decomposition");
        int cost = 0;
        JSONArray operations = result.getJSONArray("operations");
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i);
            cost += operation.getInt("cost");
            check(!operation.has("target") && !operation.has("source") && !operation.has("label"),
                    "operation payload schema");
            check(operation.getString("path").matches("(?:temporal|matrix|quantifier|normalForm\\[[0-9]+\\]\\.(?:matrix|quantifier)(?:\\[[0-9]+\\])?)(?:\\.child\\[[0-9]+\\])*"),
                    "operation path whitelist");
        }
        check(cost == result.getInt("distance"), "public operation costs sum to distance");
        check(!result.getJSONObject("trace").getBoolean("certifiedOptimalScript"), "honest trace claim");
        check(result.getJSONObject("trace").getBoolean("matrixReplayVerified"), "private metric-view replay passed");
        return result;
    }

    private static String source(String body) { return ENV + "pred target { " + body + " }\nrun target for 3\n"; }
    private static JSONObject evaluate(String student, String oracle) {
        return LiveFeedback.evaluate(new JSONObject().put("studentSource", student)
                .put("oracleSource", oracle).put("predicate", "target"));
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError("Adapter regression failed: " + message);
    }
}
