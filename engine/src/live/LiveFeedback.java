package live;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.alloy4.ErrorSyntax;
import edu.mit.csail.sdg.alloy4.ErrorType;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.LiveTrace;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import org.json.JSONArray;
import org.json.JSONObject;
import parser.ast.nodes.ModelUnit;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One request per process. No private-source text may cross this JSON boundary. */
public final class LiveFeedback {
    public static final String METRIC = "acgn-fast-rewrite-canonical-distance";
    private static final int MAX_INPUT_BYTES = 1_048_576;
    private static final int MAX_REFERENCES = 4096;
    private static final Pattern OPERATION = Pattern.compile("^(.*): (insert|delete|replace|modify) .*$");
    private static final Pattern CHILD_INDEX = Pattern.compile("\\.child\\[[0-9]+\\]");
    private static final Pattern QUANTIFIER_INDEX = Pattern.compile("\\.quantifier(\\[[0-9]+\\])");
    private LiveFeedback() { }

    public static void main(String[] args) throws Exception {
        PrintStream wire = System.out;
        // The framework and parser have historical console diagnostics. Neither stdout
        // nor stderr is a channel for them when a private reference is in memory.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        JSONObject response;
        try {
            byte[] input = System.in.readNBytes(MAX_INPUT_BYTES + 1);
            if (input.length > MAX_INPUT_BYTES) {
                response = failure("invalid_request", "REQUEST_TOO_LARGE", "The engine request exceeds its size limit.");
            } else {
                response = evaluate(new JSONObject(new String(input, StandardCharsets.UTF_8)));
            }
        } catch (Throwable error) {
            response = failure("engine_error", "ENGINE_FAILURE", "The feedback engine could not finish this request.");
        }
        wire.println(response.toString());
    }

    public static JSONObject evaluate(JSONObject request) {
        String studentSource;
        String oracleSource = null;
        String predicate;
        String referencePrefix = null, referenceSuffix = null;
        JSONArray referenceBodies = null;
        boolean poolMode = request.has("referenceBodies") || request.has("referencePrefix") || request.has("referenceSuffix");
        try {
            studentSource = requiredString(request, "studentSource");
            predicate = requiredString(request, "predicate");
            if (!predicate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("invalid predicate");
            }
            if (poolMode) {
                if (request.has("oracleSource")) throw new IllegalArgumentException("ambiguous reference mode");
                referencePrefix = requiredString(request, "referencePrefix");
                referenceSuffix = requiredString(request, "referenceSuffix");
                referenceBodies = request.getJSONArray("referenceBodies");
                if (referenceBodies.length() == 0 || referenceBodies.length() > MAX_REFERENCES)
                    throw new IllegalArgumentException("invalid pool size");
                for (int i = 0; i < referenceBodies.length(); i++) {
                    Object body = referenceBodies.get(i);
                    if (!(body instanceof String) || ((String) body).isBlank())
                        throw new IllegalArgumentException("invalid reference body");
                }
            } else oracleSource = requiredString(request, "oracleSource");
        } catch (RuntimeException error) {
            return failure("invalid_request", "INVALID_REQUEST", "A learner source, predicate name, and valid private reference input are required.");
        }

        Canonical.Prepared student;
        try {
            student = prepare(studentSource, predicate);
        } catch (Err error) {
            JSONObject response = failure("invalid", error instanceof ErrorSyntax ? "SYNTAX_ERROR"
                    : error instanceof ErrorType ? "TYPE_ERROR" : "ALLOY_ERROR",
                    error instanceof ErrorSyntax ? "Check Alloy syntax at the indicated position."
                    : error instanceof ErrorType ? "Check names, types, and relation arities at the indicated position."
                    : "The learner module could not be parsed.");
            if (error.pos != null && error.pos.y > 0 && error.pos.x > 0) {
                response.getJSONArray("diagnostics").getJSONObject(0)
                        .put("line", error.pos.y).put("column", error.pos.x);
            }
            return response;
        } catch (Throwable error) {
            return failure("unsupported", "UNSUPPORTED_FORM", "The framework could not normalize this learner predicate.");
        }

        // Every reference is parsed independently in the exercise's fixed context.
        // Completeness is mandatory: even a zero-distance winner does not stop pool
        // validation and evaluation. Invalid later candidates invalidate the request.
        Canonical.Prepared oracle = null;
        CanonicalDistance.DistanceBreakdown distance = null;
        int referenceCount = poolMode ? referenceBodies.length() : 1;
        try {
            for (int i = 0; i < referenceCount; i++) {
                String source = poolMode ? referencePrefix + referenceBodies.getString(i) + referenceSuffix : oracleSource;
                Canonical.Prepared candidate = prepare(source, predicate);
                CanonicalDistance.DistanceBreakdown candidateDistance = Canonical.distanceBreakdown(student, candidate);
                // Strict improvement preserves the first reference in the private
                // deterministic input order when several candidates have equal cost.
                if (distance == null || candidateDistance.distance() < distance.distance()) {
                    oracle = candidate;
                    distance = candidateDistance;
                }
            }
        } catch (Throwable error) {
            return poolMode ? failure("engine_error", "REFERENCE_POOL_UNAVAILABLE",
                    "The complete reference pool could not be evaluated. No partial comparison is available.")
                    : failure("engine_error", "REFERENCE_UNAVAILABLE", "The reference for this exercise could not be prepared.");
        }

        try {
            Map<String, Integer> costs = new LinkedHashMap<>();
            costs.put("temporal", distance.temporalDistance());
            costs.put("quantifier", distance.quantifierDistance());
            costs.put("matrix", distance.matrixDistance());

            // Reconstruct actual optimal matrix and quantifier choices, retaining
            // learner-node pointers. Temporal fallback remains explicitly separate.
            LiveTrace.Result reconstructed = LiveTrace.reconstruct(student, oracle, distance);
            JSONArray hints = new JSONArray();
            JSONArray oldHints = redactedOperations(Canonical.edits(student, oracle));
            for (int i = 0; i < oldHints.length(); i++) {
                if (oldHints.getJSONObject(i).getString("component").equals("temporal")) hints.put(oldHints.getJSONObject(i));
            }
            for (int i = 0; i < reconstructed.quantifier.length(); i++) hints.put(reconstructed.quantifier.getJSONObject(i));
            for (int i = 0; i < reconstructed.matrix.length(); i++) hints.put(reconstructed.matrix.getJSONObject(i));
            JSONArray operations = new JSONArray();
            JSONArray components = new JSONArray();
            boolean allConsistent = true;
            for (Map.Entry<String, Integer> entry : costs.entrySet()) {
                int hintCount = 0;
                for (int i = 0; i < hints.length(); i++) {
                    if (entry.getKey().equals(hints.getJSONObject(i).getString("component"))) hintCount++;
                }
                boolean consistent = hintCount == entry.getValue();
                allConsistent &= consistent;
                components.put(new JSONObject().put("component", entry.getKey())
                        .put("distance", entry.getValue()).put("hintCount", hintCount)
                        .put("countMatchesDistance", consistent));
                if (consistent) {
                    for (int i = 0; i < hints.length(); i++) {
                        JSONObject hint = hints.getJSONObject(i);
                        if (entry.getKey().equals(hint.getString("component"))) operations.put(hint);
                    }
                } else if (entry.getValue() > 0) {
                    operations.put(new JSONObject().put("kind", "component-edit")
                            .put("component", entry.getKey()).put("path", entry.getKey())
                            .put("cost", entry.getValue()).put("aggregate", true)
                            .put("description", "This component needs " + entry.getValue()
                                    + " edit units; the framework does not supply a matching operation trace."));
                }
            }
            Map<String, Integer> summary = new LinkedHashMap<>();
            for (int i = 0; i < operations.length(); i++) {
                String kind = operations.getJSONObject(i).getString("kind");
                summary.put(kind, summary.getOrDefault(kind, 0) + 1);
            }
            JSONObject response = new JSONObject().put("status", "ok").put("metric", METRIC)
                    .put("metricLabel", "ACGN Fast Rewrite canonical distance")
                    .put("distance", distance.distance())
                    .put("breakdown", new JSONObject(costs))
                    .put("canonicalForm", new JSONArray(Canonical.irTemporalFol(student)))
                    .put("canonicalSize", Canonical.canonicalFormSize(student))
                    .put("operations", operations).put("operationSummary", new JSONObject(summary))
                    .put("trace", new JSONObject().put("cost", distance.distance())
                            .put("matchesDistance", true).put("hasAggregates", !allConsistent)
                            .put("kind", "redacted-framework-feedback")
                            .put("matrixReplayVerified", reconstructed.matrixReplayVerified)
                            .put("matrixTraceAlgorithm", "ordered-dp-unordered-assignment-v1")
                            .put("quantifierCostVerified", reconstructed.quantifierCostVerified)
                            .put("certifiedOptimalScript", false).put("components", components)
                            .put("note", "Learner fragments and paths refer to normalized canonical structure, not exact source positions. "
                                    + "Matrix operations privately replay the metric view at its optimal cost. "
                                    + "Replacement operators are shown; reference expressions, names and values remain hidden. "
                                    + "These unit operations are not an executable source patch."))
                    .put("diagnostics", new JSONArray());
            if (poolMode) response.put("comparison", new JSONObject()
                    .put("strategy", "nearest-known-correct").put("poolSize", referenceCount)
                    .put("evaluatedCandidates", referenceCount).put("complete", true));
            return response;
        } catch (Throwable error) {
            return failure("unsupported", "COMPARISON_UNAVAILABLE", "The framework could not compare this predicate.");
        }
    }

    static Canonical.Prepared prepare(String source, String predicate) {
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), Set.of(predicate), module);
        visitor.visit(new ModelUnit(null, module), null);
        Integer forestId = visitor.getForestId(predicate);
        if (forestId == null) throw new IllegalArgumentException("predicate unavailable");
        Multigraph graph = visitor.getForest().get(forestId);
        return Canonical.prepare(graph);
    }

    static JSONArray redactedOperations(List<String> edits) {
        JSONArray result = new JSONArray();
        // Deliberately do not split on an arrow or copy any operation payload. Paths
        // are rebuilt from a whitelist, because temporal normal-form path names also
        // reveal reference operators when a whole form has been inserted.
        Map<String, String> formIds = new LinkedHashMap<>();
        for (String edit : edits) {
            if (edit.equals("no-op")) continue;
            Matcher match = OPERATION.matcher(edit);
            if (!match.matches()) continue;
            String originalPath = match.group(1);
            String kind = match.group(2);
            String component = originalPath.startsWith("temporal") ? "temporal"
                    : originalPath.contains(".quantifier") ? "quantifier" : "matrix";
            String path;
            if (component.equals("temporal")) {
                path = "temporal";
            } else {
                String marker = "." + component;
                int end = originalPath.indexOf(marker);
                String form = end < 0 ? "" : originalPath.substring(0, end);
                String formId = formIds.computeIfAbsent(form, ignored -> Integer.toString(formIds.size()));
                path = "normalForm[" + formId + "]." + component;
                Matcher quantifierIndex = QUANTIFIER_INDEX.matcher(originalPath);
                if (quantifierIndex.find()) path += quantifierIndex.group(1);
            }
            Matcher indices = CHILD_INDEX.matcher(originalPath);
            while (indices.find()) path += indices.group();
            String unit = component.equals("quantifier") ? "binding" : "node";
            String verb = kind.equals("modify") || kind.equals("replace") ? "Update" : kind.equals("insert") ? "Insert" : "Delete";
            result.put(new JSONObject().put("kind", kind).put("component", component)
                    .put("path", path).put("cost", 1).put("aggregate", false)
                    .put("description", verb + " a " + component + " " + unit + "."));
        }
        return result;
    }

    private static String requiredString(JSONObject value, String key) {
        Object field = value.get(key);
        if (!(field instanceof String) || ((String) field).isBlank()) throw new IllegalArgumentException("missing field");
        return (String) field;
    }

    private static JSONObject failure(String status, String code, String message) {
        return new JSONObject().put("status", status).put("metric", METRIC)
                .put("diagnostics", new JSONArray().put(new JSONObject()
                        .put("code", code).put("message", message)));
    }
}
