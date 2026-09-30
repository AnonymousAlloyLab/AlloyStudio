package live;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.alloy4.ErrorSyntax;
import edu.mit.csail.sdg.alloy4.ErrorType;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.RawAstTrace;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Learner-only JSON presentation for unit-cost raw AST node edits. */
final class AstFeedback {
    static final String METRIC = "acgn-raw-ast-zhang-shasha-distance";
    private record Candidate(RawAstTrace.Prepared prepared, int distance) { }
    private AstFeedback() { }

    static JSONObject evaluate(JSONObject request) {
        String source, predicate, oracle = null, prefix = null, suffix = null;
        JSONArray bodies = null;
        boolean pool = request.has("referenceBodies") || request.has("referencePrefix") || request.has("referenceSuffix");
        try {
            source = required(request, "studentSource"); predicate = required(request, "predicate");
            if (!predicate.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException();
            if (pool) {
                if (request.has("oracleSource")) throw new IllegalArgumentException();
                prefix = required(request, "referencePrefix"); suffix = required(request, "referenceSuffix");
                bodies = request.getJSONArray("referenceBodies");
                if (bodies.isEmpty() || bodies.length() > 4096) throw new IllegalArgumentException();
                for (int i = 0; i < bodies.length(); i++)
                    if (!(bodies.get(i) instanceof String value) || value.isBlank()) throw new IllegalArgumentException();
            } else oracle = required(request, "oracleSource");
        } catch (RuntimeException error) {
            return failure("invalid_request", "INVALID_REQUEST", "A learner source, predicate name, and valid private reference input are required.");
        }
        CompModule module;
        RawAstTrace.Prepared learner;
        try {
            module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
            learner = RawAstTrace.prepare(module, predicate);
        } catch (Err error) {
            JSONObject response = failure("invalid", error instanceof ErrorSyntax ? "SYNTAX_ERROR"
                    : error instanceof ErrorType ? "TYPE_ERROR" : "ALLOY_ERROR",
                    error instanceof ErrorSyntax ? "Check Alloy syntax at the indicated position."
                    : error instanceof ErrorType ? "Check names, types, and relation arities at the indicated position."
                    : "The learner module could not be parsed.");
            if (error.pos != null && error.pos.y > 0 && error.pos.x > 0)
                response.getJSONArray("diagnostics").getJSONObject(0).put("line", error.pos.y).put("column", error.pos.x);
            return response;
        } catch (Throwable error) {
            return failure("unsupported", "AST_UNAVAILABLE", "This predicate could not be represented within the raw AST limits.");
        }
        int references = pool ? bodies.length() : 1;
        int minimum, evaluatedCandidates;
        RawAstTrace.Prepared nearest;
        try {
            VerifiedPoolSelection<Candidate> selection = new VerifiedPoolSelection<>(references);
            for (int i = 0; i < references; i++) {
                String reference = pool ? prefix + bodies.getString(i) + suffix : oracle;
                RawAstTrace.Prepared candidate = RawAstTrace.prepare(
                        CompUtil.parseEverything_fromString(A4Reporter.NOP, reference), predicate);
                int distance = RawAstTrace.distance(learner, candidate);
                selection.consider(i, new Candidate(candidate, distance), distance);
            }
            VerifiedPoolSelection.Result<Candidate> selected = selection.result();
            nearest = selected.value().prepared();
            minimum = selected.value().distance();
            evaluatedCandidates = selected.evaluatedCandidates();
        } catch (Throwable error) {
            return pool ? failure("engine_error", "REFERENCE_POOL_UNAVAILABLE",
                    "The complete reference pool could not be evaluated. Retry or ask the administrator to check the analysis service. No partial comparison is available.")
                    : failure("engine_error", "REFERENCE_UNAVAILABLE", "The reference comparison could not complete. Retry or ask the administrator to check the analysis service.");
        }
        try {
            RawAstTrace.Result trace = RawAstTrace.trace(learner, nearest, minimum);
            Locations locations = new Locations(source, module, predicate);
            JSONArray operations = new JSONArray();
            Map<String, Integer> summary = new LinkedHashMap<>();
            for (RawAstTrace.Edit edit : trace.edits()) {
                JSONObject operation = operation(edit, locations);
                operations.put(operation); summary.merge(edit.kind(), 1, Integer::sum);
            }
            JSONObject result = new JSONObject().put("status", "ok").put("metric", METRIC)
                    .put("metricLabel", "Raw AST Zhang–Shasha distance").put("distance", minimum)
                    .put("breakdown", new JSONObject().put("ast", minimum)).put("astSize", learner.size())
                    .put("canonicalForm", new JSONArray()).put("operations", operations)
                    .put("operationSummary", new JSONObject(summary)).put("diagnostics", new JSONArray())
                    .put("trace", new JSONObject().put("cost", minimum).put("matchesDistance", true)
                            .put("hasAggregates", false).put("kind", "redacted-framework-feedback")
                            .put("astReplayVerified", true).put("astTraceAlgorithm", "zhang-shasha-node-edit-v1")
                            .put("certifiedOptimalScript", false)
                            .put("components", new JSONArray().put(new JSONObject().put("component", "ast")
                                    .put("distance", minimum).put("hintCount", operations.length()).put("countMatchesDistance", true)))
                            .put("note", "Each hint is one raw AST node edit. Deleting a node keeps its children in order; "
                                    + "inserting a node may wrap consecutive children. These tree edits guide your work but are not executable source patches. "
                                    + "Only your code and replacement operator names are shown; reference expressions remain hidden."));
            if (pool) result.put("comparison", new JSONObject().put("strategy", "nearest-known-correct")
                    .put("poolSize", references).put("evaluatedCandidates", evaluatedCandidates).put("complete", true));
            return result;
        } catch (Throwable error) {
            return failure("unsupported", "AST_COMPARISON_UNAVAILABLE", "The raw AST edit trace could not be verified.");
        }
    }

    private static JSONObject operation(RawAstTrace.Edit edit, Locations locations) {
        boolean insert = edit.kind().equals("insert");
        String operator = edit.sourceOperator(), kind = edit.sourceNodeKind();
        String action = insert ? "Inspect this area for a missing part; it may need to wrap existing expressions."
                : edit.kind().equals("delete") ? "Consider removing this part while keeping the expressions inside it in order."
                : edit.replacementOperator() != null ? "Consider the “" + edit.replacementOperator() + "” operator at this part of your code."
                : kind.equals("reference") || kind.equals("variable") ? "Check which name this part of your code should use."
                : kind.equals("constant") ? "Check the value used at this part of your code."
                : kind.equals("call") ? "Check which predicate or function this call should use."
                : "Review the operator or declaration at this part of your code.";
        JSONObject location = locations.location(edit.sourcePosition(), insert);
        JSONObject operation = new JSONObject().put("kind", edit.kind()).put("component", "ast")
                .put("path", "ast[" + edit.sourceIndex() + "]").put("cost", 1).put("aggregate", false)
                .put("sourceNodeKind", kind).put("sourceOperator", operator == null ? kind : operator)
                .put("sourceRole", insert ? "insertion-anchor" : "affected")
                .put("description", action).put("action", action)
                .put("reason", "The comparison points to a difference near this part of your code.")
                .put("nextStep", "Inspect the selected part, make one change yourself, and check again. Keep the surrounding expression valid.")
                .put("sourceLocation", location)
                .put("canonicalLocation", metadata("canonical", "unavailable", "related", "Canonical locations are not used in raw AST mode."));
        if (edit.replacementOperator() != null) operation.put("replacementOperator", edit.replacementOperator());
        if (location.getString("status").equals("located")) {
            JSONObject range = location.getJSONArray("ranges").getJSONObject(0);
            String text = locations.source.substring(range.getInt("start"), range.getInt("end"));
            if (text.length() > 600) {
                int end = Character.isHighSurrogate(text.charAt(596)) ? 596 : 597;
                text = text.substring(0, end) + "...";
            }
            operation.put("sourceTerm", text);
        }
        return operation;
    }

    private static final class Locations {
        final String source;
        final List<Integer> lines = new ArrayList<>();
        Pos body;
        int bodyStart, bodyEnd;
        Locations(String source, CompModule module, String predicate) {
            this.source = source; lines.add(0);
            for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n') lines.add(i + 1);
            for (Func function : module.getAllFunc())
                if (function.isPred && (function.label.equals(predicate) || function.label.equals("this/" + predicate))) {
                    body = function.getBody().span();
                    int[] range = offsets(body);
                    if (range != null) { bodyStart = range[0]; bodyEnd = range[1]; }
                    break;
                }
        }
        int[] offsets(Pos position) {
            if (position == null || body == null || !Objects.equals(position.filename, body.filename)
                    || position.y < 1 || position.y2 < position.y || position.y2 > lines.size()
                    || position.x < 1 || position.x2 < 1) return null;
            int start = lines.get(position.y - 1) + position.x - 1, end = lines.get(position.y2 - 1) + position.x2;
            int startLimit = position.y < lines.size() ? lines.get(position.y) : source.length();
            int endLimit = position.y2 < lines.size() ? lines.get(position.y2) : source.length();
            return start < 0 || start >= startLimit || end <= start || end > endLimit ? null : new int[] {start, end};
        }
        JSONObject location(Pos position, boolean anchor) {
            int[] range = offsets(position);
            if (range == null || range[0] < bodyStart || range[1] > bodyEnd)
                return metadata("module", "unavailable", "related", "An exact learner source occurrence is unavailable for this AST node.");
            return metadata("module", "located", "node", anchor
                    ? "Inspect this learner node as context for an insertion; it does not specify where to add code."
                    : "This source occurrence is the learner AST node selected by the edit.")
                    .put("ranges", new JSONArray().put(new JSONObject().put("start", range[0]).put("end", range[1])));
        }
    }

    private static JSONObject metadata(String coordinates, String status, String precision, String reason) {
        return new JSONObject().put("status", status).put("precision", precision).put("coordinateSystem", coordinates)
                .put("offsetEncoding", "utf-16").put("reason", reason).put("ranges", new JSONArray());
    }
    private static String required(JSONObject input, String name) {
        Object value = input.get(name);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException();
        return text;
    }
    private static JSONObject failure(String status, String code, String message) {
        return new JSONObject().put("status", status).put("diagnostics", new JSONArray()
                .put(new JSONObject().put("code", code).put("message", message)));
    }
}
