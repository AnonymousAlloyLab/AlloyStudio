package live;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/** Finite runtime checks, separate from the proof of the generated policies. */
public final class PoolBridgeSelfTest {
    private static final String ENV = "module pool_bridge_fixture\nsig A { r: set A }\n";
    private static int checks;
    private static int pools;

    public static void main(String[] args) {
        PrintStream report = System.out, errors = System.err;
        try (PrintStream discard = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(discard);
            System.setErr(discard);
            finitePolicies();
            exhaustivePools();
            invalidAccumulation();
            for (String metric : List.of("canonical", "ast")) liveMetric(metric);
        } finally {
            System.setOut(report);
            System.setErr(errors);
        }
        report.println("PoolBridgeSelfTest passed (" + pools + " exhaustive pools, " + checks + " checks)");
    }

    private static void finitePolicies() {
        for (boolean first : new boolean[] {false, true}) {
            for (boolean second : new boolean[] {false, true}) {
                check(BridgePolicies.choose(first, second) == (!first || second), "choice policy table");
                check(BridgePolicies.finish(first, second) == (first && second), "completion policy table");
            }
        }
    }

    private static void exhaustivePools() {
        for (int size = 1; size <= 6; size++) enumerate(new int[size], 0);
        checkPool(new int[] {Integer.MAX_VALUE});
        checkPool(new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1, 0, 0});
    }

    private static void enumerate(int[] costs, int at) {
        if (at == costs.length) { checkPool(costs); return; }
        for (int cost = 0; cost <= 3; cost++) {
            costs[at] = cost;
            enumerate(costs, at + 1);
        }
    }

    private static void checkPool(int[] costs) {
        VerifiedPoolSelection<Integer> selection = new VerifiedPoolSelection<>(costs.length);
        for (int index = 0; index < costs.length; index++) {
            rejects(selection::result, "no result from a proper prefix, including zero minima");
            selection.consider(index, index, costs[index]);
        }
        // Independent batch oracle: sort a copy and locate the first occurrence.
        int[] sorted = costs.clone();
        Arrays.sort(sorted);
        int first = 0;
        while (costs[first] != sorted[0]) first++;
        VerifiedPoolSelection.Result<Integer> result = selection.result();
        check(result.cost() == sorted[0], "minimum matches sorted batch oracle");
        check(result.index() == first && result.value() == first, "first equal minimum retains its candidate");
        check(result.evaluatedCandidates() == costs.length, "published count covers every candidate");
        check(selection.result().equals(result), "completed result is repeatable");
        pools++;
    }

    private static void invalidAccumulation() {
        rejects(() -> new VerifiedPoolSelection<>(0), "empty pool rejected");
        rejects(() -> new VerifiedPoolSelection<>(-1), "negative pool count rejected");
        rejects(() -> new VerifiedPoolSelection<>(Integer.MIN_VALUE), "minimum int pool count rejected");
        rejects(new VerifiedPoolSelection<>(Integer.MAX_VALUE)::result, "large count never appears complete initially");
        for (int cost : new int[] {-1, Integer.MIN_VALUE}) {
            VerifiedPoolSelection<String> selection = new VerifiedPoolSelection<>(2);
            selection.consider(0, "zero", 0);
            rejects(() -> selection.consider(1, "invalid", cost), "negative cost rejected after zero");
            rejects(selection::result, "bad cost invalidates pool");
            rejects(() -> selection.consider(1, "replacement", 1), "bad cost failure is sticky");
            rejects(selection::result, "retry cannot publish after bad cost");
        }
        for (int index : new int[] {-1, 1, Integer.MAX_VALUE}) {
            VerifiedPoolSelection<String> selection = new VerifiedPoolSelection<>(2);
            rejects(() -> selection.consider(index, "unordered", 1), "first candidate must have index zero");
            rejects(() -> selection.consider(0, "replacement", 1), "invalid ordering failure is sticky");
            rejects(selection::result, "invalid ordering cannot publish");
        }
        VerifiedPoolSelection<String> duplicate = new VerifiedPoolSelection<>(2);
        duplicate.consider(0, "same", 0);
        rejects(() -> duplicate.consider(0, "same", 0), "duplicate index rejected even at zero");
        rejects(() -> duplicate.consider(1, "last", 1), "duplicate cannot be repaired by later candidate");
        rejects(duplicate::result, "duplicate invalidates completion");
        VerifiedPoolSelection<String> missing = new VerifiedPoolSelection<>(3);
        missing.consider(0, "first", 0);
        rejects(() -> missing.consider(2, "third", 0), "skipped index rejected");
        rejects(missing::result, "skipped index never completes");
        VerifiedPoolSelection<String> nullValue = new VerifiedPoolSelection<>(1);
        rejects(() -> nullValue.consider(0, null, 0), "null candidate rejected");
        rejects(nullValue::result, "null candidate never completes");
        VerifiedPoolSelection<String> extra = new VerifiedPoolSelection<>(1);
        extra.consider(0, "first", Integer.MAX_VALUE);
        check(extra.result().value().equals("first"), "maximum valid cost is selectable");
        rejects(() -> extra.consider(1, "extra", 0), "candidate beyond declared count rejected");
        rejects(extra::result, "extra candidate invalidates subsequent publication");
    }

    private static void liveMetric(String metric) {
        JSONObject nearest = LiveFeedback.evaluate(request(metric, "no A", List.of("all x:A | some x.r", "some A")));
        complete(nearest, 2);
        check(nearest.getInt("distance") == 1, "actual metric selects later strict improvement");
        check(operator(nearest).equals("some"), "actual metric traces selected candidate");
        JSONObject first = LiveFeedback.evaluate(request(metric, "some A", List.of("no A", "one A")));
        JSONObject reverse = LiveFeedback.evaluate(request(metric, "some A", List.of("one A", "no A")));
        complete(first, 2);
        complete(reverse, 2);
        check(first.getInt("distance") == reverse.getInt("distance"), "actual metric tie costs agree");
        check(operator(first).equals("no") && operator(reverse).equals("one"), "actual metric ties follow input order");
        JSONObject zero = LiveFeedback.evaluate(request(metric, "some A", List.of("some A", "some A", "no A")));
        complete(zero, 3);
        check(zero.getInt("distance") == 0 && zero.getJSONArray("operations").isEmpty(), "zero winner retains complete pool");
        for (List<String> bodies : List.of(List.of("some A", "some PRIVATE_POOL_BRIDGE"),
                List.of("some PRIVATE_POOL_BRIDGE", "some A"))) {
            JSONObject invalid = LiveFeedback.evaluate(request(metric, "some A", bodies));
            check(invalid.getString("status").equals("engine_error"), "invalid reference rejects entire pool");
            check(invalid.getJSONArray("diagnostics").getJSONObject(0).getString("code").equals("REFERENCE_POOL_UNAVAILABLE"),
                    "reference pool failure uses sanitized boundary");
            check(!invalid.has("distance") && !invalid.has("comparison") && !invalid.has("operations"),
                    "failed pool publishes no partial result");
            check(!invalid.toString().contains("PRIVATE_POOL_BRIDGE"), "failed pool keeps reference text private");
        }
        JSONObject single = LiveFeedback.evaluate(new JSONObject().put("metric", metric)
                .put("studentSource", source("no A")).put("oracleSource", source("some A")).put("predicate", "target"));
        check(single.getString("status").equals("ok") && single.getInt("distance") == 1,
                "single reference uses same accumulator successfully");
        check(!single.has("comparison"), "single reference retains response shape");
    }

    private static JSONObject request(String metric, String learner, List<String> references) {
        return new JSONObject().put("metric", metric).put("studentSource", source(learner)).put("predicate", "target")
                .put("referenceBodies", new JSONArray(references)).put("referencePrefix", ENV + "pred target {\n")
                .put("referenceSuffix", "\n}\n");
    }

    private static String source(String body) { return ENV + "pred target {\n" + body + "\n}\n"; }

    private static String operator(JSONObject result) {
        return result.getJSONArray("operations").getJSONObject(0).getString("replacementOperator");
    }

    private static void complete(JSONObject result, int count) {
        check(result.getString("status").equals("ok"), "actual metric pool succeeds");
        JSONObject comparison = result.getJSONObject("comparison");
        check(comparison.getInt("poolSize") == count && comparison.getInt("evaluatedCandidates") == count
                && comparison.getBoolean("complete"), "actual metric reports complete evaluation");
        check(comparison.length() == 4, "private winning index absent from public metadata");
    }

    private static void rejects(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
