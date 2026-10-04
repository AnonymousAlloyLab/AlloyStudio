package is.fivefivefive.CanDis;

import org.json.JSONArray;
import org.json.JSONObject;

/** Focused end-to-end smoke checks for predicate and function visualization. */
public final class VisualizationAnalysisServiceTest {
    private static final String MODEL = String.join("\n",
            "sig User { follows: set User }",
            "fun neighbors[u: User]: set User { u.follows }",
            "pred hasNeighbor[u: User] { some neighbors[u] }",
            "fun emptyPair : univ -> univ { none -> none }");

    private VisualizationAnalysisServiceTest() {
    }

    public static void main(String[] args) {
        VisualizationAnalysisService service = new VisualizationAnalysisService();
        JSONObject inspection = service.inspect(MODEL);
        JSONArray callables = inspection.getJSONArray("callables");
        if (callables.length() != 3) {
            throw new AssertionError("Inspection leaked parser-internal callables: " + callables);
        }
        assertCallable(callables, "neighbors", "function");
        assertCallable(callables, "hasNeighbor", "predicate");
        assertCallable(callables, "emptyPair", "function");

        assertAnalysis(service.analyze(MODEL, "neighbors", "function"), "neighbors", "function");
        assertAnalysis(service.analyze(MODEL, "hasNeighbor", "predicate"), "hasNeighbor", "predicate");
        assertEmptyRelationArity(
                service.analyze(MODEL, "emptyPair", "function"), 2);
        assertComparison(service.compare(
                MODEL, "neighbors", "function", "hasNeighbor", "predicate"), false);
        assertComparison(service.compare(
                MODEL, "neighbors", "function", "neighbors", "function"), true);
        System.out.println("VisualizationAnalysisServiceTest passed");
    }

    private static void assertEmptyRelationArity(
            JSONObject analysis,
            int expectedArity) {
        JSONArray classes = analysis.getJSONObject("graph").getJSONArray("eclasses");
        for (int classIndex = 0; classIndex < classes.length(); classIndex++) {
            JSONObject eclass = classes.getJSONObject(classIndex);
            if (isEmptyRelationOfArity(eclass.getJSONObject("type"), expectedArity)) {
                return;
            }
            JSONArray nodes = eclass.getJSONArray("nodes");
            for (int nodeIndex = 0; nodeIndex < nodes.length(); nodeIndex++) {
                if (isEmptyRelationOfArity(
                        nodes.getJSONObject(nodeIndex).getJSONObject("type"),
                        expectedArity)) {
                    return;
                }
            }
        }
        throw new AssertionError(
                "Visualization omitted empty relation arity " + expectedArity
                        + ": " + analysis);
    }

    private static boolean isEmptyRelationOfArity(
            JSONObject type,
            int expectedArity) {
        return type.optBoolean("empty", false)
                && type.optInt("arity", -1) == expectedArity
                && type.getJSONArray("columns").length() == 0;
    }

    private static void assertCallable(JSONArray values, String name, String kind) {
        for (int index = 0; index < values.length(); index++) {
            JSONObject callable = values.getJSONObject(index);
            if (name.equals(callable.getString("name")) && kind.equals(callable.getString("kind"))) {
                if (kind.equals("function")
                        && callable.optString("returnType", "").contains("parser.ast.nodes")) {
                    throw new AssertionError("Function return type is not presentation-ready: " + callable);
                }
                return;
            }
        }
        throw new AssertionError("Missing " + kind + " " + name + " in inspection response: " + values);
    }

    private static void assertAnalysis(JSONObject analysis, String name, String kind) {
        JSONObject callable = analysis.getJSONObject("callable");
        if (!name.equals(callable.getString("name")) || !kind.equals(callable.getString("kind"))) {
            throw new AssertionError("Unexpected callable metadata: " + callable);
        }
        if (callable.getString("canonicalText").contains("canonical-alloy-form")
                || !callable.has("certifiedStableForm")) {
            throw new AssertionError("Certified machine form leaked into presentation text: " + callable);
        }
        JSONObject graph = analysis.getJSONObject("graph");
        JSONArray classes = graph.getJSONArray("eclasses");
        if (classes.length() == 0) {
            throw new AssertionError("Visualization graph has no e-classes for " + name);
        }
        String root = graph.getString("rootEClassId");
        for (int index = 0; index < classes.length(); index++) {
            JSONObject eclass = classes.getJSONObject(index);
            JSONArray nodes = eclass.getJSONArray("nodes");
            for (int nodeIndex = 0; nodeIndex < nodes.length(); nodeIndex++) {
                JSONObject node = nodes.getJSONObject(nodeIndex);
                if (node.getString("displayName").startsWith("ALLOY/")) {
                    throw new AssertionError("Certified operator leaked into graph label: " + node);
                }
                if (!node.getJSONObject("attributes").has("certifiedOperator")) {
                    throw new AssertionError("Graph presentation discarded certified operator: " + node);
                }
            }
            if (root.equals(eclass.getString("id"))) {
                return;
            }
        }
        throw new AssertionError("Visualization root " + root + " is absent for " + name);
    }

    private static void assertComparison(JSONObject comparison, boolean equivalent) {
        JSONObject left = comparison.getJSONObject("left");
        JSONObject right = comparison.getJSONObject("right");
        if (!left.has("canonicalText") || !right.has("canonicalText")) {
            throw new AssertionError("Comparison omitted callable representations: " + comparison);
        }
        if (!left.has("certifiedStableForm")
                || !right.has("certifiedStableForm")
                || left.getString("canonicalText").contains("canonical-alloy-form")) {
            throw new AssertionError("Comparison representation is not presentation-safe: " + comparison);
        }
        String leftStable = left.getString("certifiedStableForm");
        String rightStable = right.getString("certifiedStableForm");
        if (leftStable.isBlank() || rightStable.isBlank()) {
            throw new AssertionError("Comparison omitted a nonempty certified stable form: " + comparison);
        }
        JSONObject distance = comparison.getJSONObject("distance");
        if (!distance.getBoolean("exactForStoredOrbits")) {
            throw new AssertionError("Comparison exposed a bounded result as certified: " + comparison);
        }
        int total = distance.getInt("total");
        if (total != distance.getInt("temporal")
                        + distance.getInt("quantifier")
                        + distance.getInt("matrix")) {
            throw new AssertionError("Distance components do not sum to total: " + distance);
        }
        int operationCost = 0;
        JSONArray operations = comparison.getJSONArray("operations");
        for (int index = 0; index < operations.length(); index++) {
            operationCost += operations.getJSONObject(index).getInt("cost");
        }
        if (operationCost != total) {
            throw new AssertionError("Operation costs do not witness the distance: " + comparison);
        }
        boolean certifiedEquivalent = comparison.getBoolean("certifiedEquivalent");
        boolean stableFormsEqual = leftStable.equals(rightStable);
        if (certifiedEquivalent != stableFormsEqual) {
            throw new AssertionError(
                    "Certified equality did not follow certifiedStableForm: " + comparison);
        }
        if (certifiedEquivalent != (total == 0)) {
            throw new AssertionError("Certified zero kernel mismatch: " + comparison);
        }
        if (certifiedEquivalent != equivalent) {
            throw new AssertionError("Unexpected comparison semantics: " + comparison);
        }
    }
}
