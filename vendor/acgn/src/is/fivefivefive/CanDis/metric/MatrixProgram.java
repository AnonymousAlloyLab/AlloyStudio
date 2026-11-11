package is.fivefivefive.CanDis.metric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Detached occurrence-store execution after MatrixReplay has checked native premises. */
final class MatrixProgram {
    private MatrixProgram() { }

    private record Cell(String address, JSONObject payload) { }
    private record Step(Cell source, Cell target, int cost, String premise) { }
    private static final class Layout {
        final Map<String, JSONObject> source = new LinkedHashMap<>();
        final Map<String, JSONObject> target = new LinkedHashMap<>();
        final List<Step> steps = new ArrayList<>();
        final JSONArray selections = new JSONArray();
        String sourceRoot;
        String targetRoot;
    }

    static JSONObject capture(JSONObject receipt) {
        Layout layout = layout(receipt);
        JSONArray operations = new JSONArray();
        for (Step step : layout.steps) operations.put(operation(step));
        JSONObject program = new JSONObject().put("schema", "acgn-matrix-program-v1")
                .put("sourceSha256", receipt.getString("sourceSha256"))
                .put("targetSha256", receipt.getString("targetSha256"))
                .put("sourceRoot", layout.sourceRoot == null ? JSONObject.NULL : layout.sourceRoot)
                .put("targetRoot", layout.targetRoot == null ? JSONObject.NULL : layout.targetRoot)
                .put("representationSteps", layout.selections).put("operations", operations)
                .put("cost", receipt.getInt("cost"));
        verify(receipt, program);
        return program.put("executionChecked", true);
    }

    /** No optimizer, no Node objects and no return-target shortcut. */
    static int verify(JSONObject receipt, JSONObject program) {
        require("acgn-matrix-program-v1".equals(program.getString("schema")), "schema");
        require(receipt.getString("sourceSha256").equals(program.getString("sourceSha256"))
                && receipt.getString("targetSha256").equals(program.getString("targetSha256")), "endpoints");
        Layout layout = layout(receipt);
        require(java.util.Objects.equals(layout.sourceRoot, program.isNull("sourceRoot") ? null : program.getString("sourceRoot"))
                && java.util.Objects.equals(layout.targetRoot, program.isNull("targetRoot") ? null : program.getString("targetRoot")), "root occurrences");
        require(layout.selections.similar(program.getJSONArray("representationSteps")), "representation premises");
        Map<String, JSONObject> remaining = new LinkedHashMap<>(layout.source);
        Map<String, JSONObject> produced = new LinkedHashMap<>();
        JSONArray operations = program.getJSONArray("operations");
        require(operations.length() == layout.steps.size(), "operation coverage");
        int cost = 0;
        for (int i = 0; i < operations.length(); i++) {
            JSONObject op = operations.getJSONObject(i);
            try {
                // The checked derivation fixes addresses, charges, and zero-cost correspondences.
                require(operation(layout.steps.get(i)).similar(op), "selected decision/payload/charge");
                if (!op.isNull("source")) {
                    JSONObject before = remaining.remove(op.getString("source"));
                    require(before != null && before.similar(op.getJSONObject("before")), "stale source occurrence");
                }
                if (!op.isNull("target")) {
                    JSONObject payload = new JSONObject(op.getJSONObject("after").toString());
                    require(produced.putIfAbsent(op.getString("target"), payload) == null, "repeated target occurrence");
                }
                cost = Math.addExact(cost, DeclarationReplay.integer(op, "cost"));
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("MATRIX_PROGRAM operation " + i + ": " + failure.getMessage(), failure);
            }
        }
        require(remaining.isEmpty(), "unconsumed source");
        require(produced.keySet().equals(layout.target.keySet()), "target occurrence coverage");
        for (var entry : produced.entrySet()) require(entry.getValue().similar(layout.target.get(entry.getKey())),
                "reconstructed target payload at " + entry.getKey());
        require(reachable(produced, layout.targetRoot).equals(produced.keySet()), "reconstructed target edges");
        require(cost == DeclarationReplay.integer(program, "cost") && cost == receipt.getInt("cost"), "total");
        return cost;
    }

    private static JSONObject operation(Step step) {
        return new JSONObject().put("kind", step.source == null ? "insert" : step.target == null ? "delete"
                : step.cost == 0 ? step.source.payload.similar(step.target.payload) ? "read-keep" : "metric-align" : "update")
                .put("source", step.source == null ? JSONObject.NULL : step.source.address)
                .put("target", step.target == null ? JSONObject.NULL : step.target.address)
                .put("before", step.source == null ? JSONObject.NULL : step.source.payload)
                .put("after", step.target == null ? JSONObject.NULL : step.target.payload)
                .put("cost", step.cost).put("premise", step.premise);
    }

    private static Layout layout(JSONObject receipt) {
        Layout out = new Layout();
        out.sourceRoot = receipt.getJSONArray("source").isEmpty() ? null : selectedPath("root", receipt.getJSONObject("derivation"), true);
        out.targetRoot = receipt.getJSONArray("target").isEmpty() ? null : selectedPath("root", receipt.getJSONObject("derivation"), false);
        walk(receipt.getJSONArray("source"), receipt.getJSONArray("target"),
                "root", "root", receipt.getJSONObject("derivation"), "derivation", out);
        return out;
    }

    private static void walk(JSONArray a, JSONArray b, String ap, String bp,
            JSONObject proof, String premise, Layout out) {
        if (proof.getString("kind").equals("select")) {
            int ai = proof.getInt("leftAlternative"), bi = proof.getInt("rightAlternative");
            out.selections.put(new JSONObject().put("source", a.isEmpty() ? JSONObject.NULL : ap)
                    .put("target", b.isEmpty() ? JSONObject.NULL : bp).put("leftAlternative", ai)
                    .put("rightAlternative", bi).put("premise", premise).put("cost", 0));
            walk(ai < 0 ? a : a.getJSONArray(2).getJSONArray(ai), bi < 0 ? b : b.getJSONArray(2).getJSONArray(bi),
                    ai < 0 ? ap : ap + "/alternative/" + ai, bi < 0 ? bp : bp + "/alternative/" + bi,
                    proof.getJSONObject("selected"), premise + "/selected", out);
            return;
        }
        if (a.isEmpty() || b.isEmpty()) {
            if (!a.isEmpty()) whole(a, ap, true, premise, out);
            if (!b.isEmpty()) whole(b, bp, false, premise, out);
            return;
        }
        out.steps.add(new Step(cell(a, ap, out.source, proof, true), cell(b, bp, out.target, proof, false), proof.getInt("headCost"), premise));
        JSONArray children = proof.getJSONArray("children");
        for (int k = 0; k < children.length(); k++) {
            JSONObject edge = children.getJSONObject(k);
            int i = edge.getInt("leftIndex"), j = edge.getInt("rightIndex");
            walk(i < 0 ? new JSONArray() : a.getJSONArray(1).getJSONArray(i),
                    j < 0 ? new JSONArray() : b.getJSONArray(1).getJSONArray(j),
                    ap + "/child/" + i, bp + "/child/" + j, edge.getJSONObject("edit"),
                    premise + "/children/" + k + "/edit", out);
        }
    }

    private static void whole(JSONArray node, String path, boolean deletion, String premise, Layout out) {
        Cell cell = cell(node, path, deletion ? out.source : out.target, null, deletion);
        out.steps.add(new Step(deletion ? cell : null, deletion ? null : cell, 1, premise));
        JSONArray children = node.getJSONArray(1);
        for (int i = 0; i < children.length(); i++) whole(children.getJSONArray(i), path + "/child/" + i,
                deletion, premise, out);
    }

    private static Cell cell(JSONArray node, String path, Map<String, JSONObject> store, JSONObject proof, boolean source) {
        JSONArray children = new JSONArray();
        for (int i = 0; i < node.getJSONArray(1).length(); i++) children.put(path + "/child/" + i);
        if (proof != null) for (Object value : proof.getJSONArray("children")) {
            JSONObject edge = (JSONObject)value;
            int index = edge.getInt(source ? "leftIndex" : "rightIndex");
            if (index >= 0) children.put(index, selectedPath(path + "/child/" + index, edge.getJSONObject("edit"), source));
        }
        JSONObject payload = new JSONObject().put("head", node.getString(0)).put("children", children)
                .put("certifiedAlternatives", new JSONArray(node.getJSONArray(2).toString()))
                .put("nativeEvidence",node.getString(3));
        require(store.putIfAbsent(path, payload) == null, "duplicate occurrence " + path);
        return new Cell(path, payload);
    }

    private static String selectedPath(String path, JSONObject proof, boolean source) {
        while (proof.getString("kind").equals("select")) {
            int index = proof.getInt(source ? "leftAlternative" : "rightAlternative");
            if (index >= 0) path += "/alternative/" + index;
            proof = proof.getJSONObject("selected");
        }
        return path;
    }

    private static java.util.Set<String> reachable(Map<String,JSONObject> cells, String root) {
        var seen = new java.util.HashSet<String>();
        var pending = new java.util.ArrayDeque<String>();
        if (root != null) pending.add(root);
        while (!pending.isEmpty()) {
            String address = pending.removeFirst();
            require(seen.add(address), "cyclic or repeated target occurrence");
            JSONObject cell = cells.get(address);
            require(cell != null, "dangling target edge");
            for (Object child : cell.getJSONArray("children")) pending.add((String)child);
        }
        return seen;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("MATRIX_PROGRAM: " + message);
    }
}
