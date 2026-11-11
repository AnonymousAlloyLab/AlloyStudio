package is.fivefivefive.CanDis.metric;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;

public final class TemporalReplayTest {
    private static int checks;
    private record Tree(String label, List<Tree> children) { }
    private static final OrderedTreeEditDistance.Adapter<Tree> ADAPTER =
            new OrderedTreeEditDistance.Adapter<>() {
                public String label(Tree tree) { return tree.label; }
                public List<Tree> children(Tree tree) { return tree.children; }
            };
    private static Tree tree(String label, Tree... children) { return new Tree(label, List.of(children)); }
    private static void check(boolean value) { if (!value) throw new AssertionError("check " + checks); checks++; }
    private static void reject(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Accepted invalid temporal receipt " + checks);
    }
    private static JSONObject copy(JSONObject object) { return new JSONObject(object.toString()); }
    private static JSONObject receipt(Tree a, Tree b, int cost) {
        check(OrderedTreeEditDistance.distance(a, b, ADAPTER) == cost);
        JSONObject result = TemporalReplay.capture(a, b, cost, Tree::label, Tree::children);
        check(TemporalReplay.verify(a, b, Tree::label, Tree::children, result) == cost);
        check(result.getJSONArray("operations").length() == cost);
        return result;
    }
    private static Tree random(Random random, int size) {
        List<Tree> children = new ArrayList<>();
        for (int remaining = size - 1; remaining > 0;) {
            int count = 1 + random.nextInt(remaining);
            children.add(random(random, count)); remaining -= count;
        }
        return new Tree("" + (char) ('a' + random.nextInt(3)), List.copyOf(children));
    }

    public static void main(String[] args) {
        Tree a = tree("root", tree("always", tree("p"), tree("q")));
        Tree b = tree("root", tree("p"), tree("q"));
        JSONObject deletion = receipt(a, b, 1);
        check(deletion.getJSONArray("operations").getJSONObject(0).getInt("childCount") == 2);
        JSONObject insertion = receipt(b, a, 1);
        check(insertion.getJSONArray("operations").getJSONObject(0).getInt("childCount") == 2);
        receipt(tree("always", tree("p")), tree("p"), 1);
        receipt(tree("p"), tree("always", tree("p")), 1);
        receipt(tree("p"), tree("q"), 1);
        receipt(a, a, 0);
        receipt(tree("r", tree("a"), tree("b")), tree("r", tree("b"), tree("a")), 2);
        receipt(tree("r"), tree("r", tree("a"), tree("b")), 2);
        JSONObject missing = copy(deletion);
        missing.getJSONArray("operations").remove(0);
        reject(() -> TemporalReplay.verify(a, b, Tree::label, Tree::children, missing));
        JSONObject wrongPath = copy(deletion);
        wrongPath.getJSONArray("operations").getJSONObject(0).put("path", new JSONArray().put(0));
        reject(() -> TemporalReplay.verify(a, b, Tree::label, Tree::children, wrongPath));
        JSONObject wrongLabel = copy(deletion);
        wrongLabel.getJSONArray("operations").getJSONObject(0).put("label", "tampered");
        reject(() -> TemporalReplay.verify(a, b, Tree::label, Tree::children, wrongLabel));
        JSONObject wrongCount = copy(insertion);
        wrongCount.getJSONArray("operations").getJSONObject(0).put("childCount", 1);
        reject(() -> TemporalReplay.verify(b, a, Tree::label, Tree::children, wrongCount));
        JSONObject wrongCost = copy(deletion).put("cost", 4_294_967_297L);
        reject(() -> TemporalReplay.verify(a, b, Tree::label, Tree::children, wrongCost));
        JSONObject fractional = copy(deletion);
        fractional.getJSONArray("operations").getJSONObject(0).put("cost", 1.5);
        reject(() -> TemporalReplay.verify(a, b, Tree::label, Tree::children, fractional));
        JSONObject endpoint = copy(deletion);
        endpoint.getJSONArray("target").put(0, "substituted");
        reject(() -> TemporalReplay.verify(a, b, Tree::label, Tree::children, endpoint));
        reject(() -> TemporalReplay.capture(a, b, 0, Tree::label, Tree::children));
        check(TemporalReplay.verify(a, b, Tree::label, Tree::children, deletion) == 1);
        Tree wide = new Tree("wide", java.util.Collections.nCopies(256, tree("leaf")));
        check(TemporalReplay.capture(wide, wide, 0, Tree::label, Tree::children).getInt("replayedCost")==0);
        Tree deep = tree("leaf");
        for (int i = 0; i < 129; i++) deep = tree("parent", deep);
        Tree excessive = deep;
        check(TemporalReplay.capture(excessive, excessive, 0, Tree::label, Tree::children).getInt("replayedCost")==0);
        check(OrderedTreeEditDistance.alignment(excessive,excessive,ADAPTER).cost()==0);
        check(OrderedTreeEditDistance.alignment(wide,wide,ADAPTER).cost()==0);
        reject(() -> TraceResources.requireCells(1_000_000,1_000_000));
        Random random = new Random(20261107L);
        for (int i = 0; i < 1000; i++) {
            Tree left = random(random, 1 + random.nextInt(16));
            Tree right = random(random, 1 + random.nextInt(16));
            int cost = OrderedTreeEditDistance.distance(left, right, ADAPTER);
            JSONObject forward = receipt(left, right, cost);
            receipt(right, left, cost);
            check(forward.similar(TemporalReplay.capture(left, right, cost, Tree::label, Tree::children)));
        }
        System.out.println("TemporalReplayTest: " + checks + " checks passed");
    }
}
