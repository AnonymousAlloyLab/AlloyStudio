package is.fivefivefive.ACGN.repair;

import edu.mit.csail.sdg.ast.ExprQt;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public structural action heads. Final parsing/type checks remain mandatory. */
final class FeedbackActions {
    private FeedbackActions() { }
    static JSONObject empty() {
        JSONObject result = new JSONObject();
        for (String name : new String[] {"controls", "sites", "deletions", "holes", "copies", "symbols",
                "variables", "draftVariables", "feedbackSymbols", "constants", "operators", "quantifiers", "counts",
                "disjointness", "cardinalities"}) result.put(name, new JSONArray());
        return result;
    }
    static void add(JSONObject into, String table, int... row) {
        into.getJSONArray(table).put(FeedbackSourceGraph.row(row));
    }
    static JSONObject choices(FeedbackSourceGraph graph, FeedbackDraft draft,
                              java.util.List<edu.mit.csail.sdg.ast.Expr> returned, boolean enabled) {
        if (!enabled) return empty();
        if (draft != null) return draft.choices(returned);
        JSONObject result = empty();
        for (var control : new FeedbackRepairSession.Control[] {FeedbackRepairSession.Control.VALIDATE,
                FeedbackRepairSession.Control.STOP, FeedbackRepairSession.Control.ABSTAIN}) {
            add(result, "controls", control.ordinal());
        }
        for (int site = 0; site < graph.expressions.size(); site++) {
            if (graph.spans.get(site)[0] < 0) continue;
            boolean temporal = FeedbackSourceGraph.temporal(graph.expressions.get(site));
            boolean binding = graph.expressions.get(site) instanceof ExprQt;
            for (FeedbackDraft.Family family : FeedbackDraft.Family.values()) {
                if (family.name().endsWith("DELETE")
                        || family == FeedbackDraft.Family.TEMPORAL_UPDATE && !temporal
                        || family == FeedbackDraft.Family.BINDING_UPDATE && !binding) continue;
                add(result, "sites", family.ordinal(), site);
            }
            for (int child : graph.children.get(site)) {
                if (graph.spans.get(child)[0] < 0 || !graph.canCopy(child, site)) continue;
                for (var family : new FeedbackDraft.Family[] {FeedbackDraft.Family.MATRIX_DELETE,
                        FeedbackDraft.Family.TEMPORAL_DELETE, FeedbackDraft.Family.BINDING_DELETE}) {
                    if (family == FeedbackDraft.Family.TEMPORAL_DELETE && !temporal
                            || family == FeedbackDraft.Family.BINDING_DELETE && !binding) continue;
                    add(result, "deletions", family.ordinal(), site, child);
                }
            }
        }
        if (!result.getJSONArray("sites").isEmpty()) add(result, "controls", FeedbackRepairSession.Control.BEGIN.ordinal());
        if (!result.getJSONArray("deletions").isEmpty()) add(result, "controls", FeedbackRepairSession.Control.DELETE.ordinal());
        return result;
    }
    static int rows(JSONObject choices) {
        int rows = 0;
        for (String key : choices.keySet()) rows = Math.addExact(rows, choices.getJSONArray(key).length());
        return rows;
    }
}
