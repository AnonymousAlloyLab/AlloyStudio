package is.fivefivefive.CanDis;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Independent finite oracle: enumerate every ancestry/order preserving mapping.
 * It deliberately uses preorder indices and parent walks, not the production
 * postorder forest dynamic program. Synthetic labels are public test data.
 */
public final class RawAstTraceSelfTest {
    private static int checks;
    private static RawAstTrace.Tree tree(String label, RawAstTrace.Tree... children) {
        return new RawAstTrace.Tree(label, List.of(children), null);
    }

    public static void main(String[] args) throws Exception {
        List<RawAstTrace.Tree> trees = new ArrayList<>();
        for (int size = 1; size <= 4; size++) trees.addAll(trees(size));
        int pairs = 0;
        for (RawAstTrace.Tree left : trees) for (RawAstTrace.Tree right : trees) {
            RawAstTrace.Prepared l = new RawAstTrace.Prepared(left), r = new RawAstTrace.Prepared(right);
            int expected = oracle(left, right);
            check(RawAstTrace.distance(l, r) == expected, "authoritative distance matches exhaustive mappings");
            RawAstTrace.Result result = RawAstTrace.trace(l, r, expected);
            check(result.distance() == expected && result.edits().size() == expected, "private replay and atomic cost");
            pairs++;
        }
        RawAstTrace.Tree leaf = tree("a");
        checkDistance(tree("b", leaf), tree("a"), 1); // Delete root, promote child.
        checkDistance(tree("a", tree("x", tree("b"), tree("c")), tree("d")),
                tree("a", tree("b"), tree("c"), tree("d")), 1);
        checkDistance(tree("a", tree("b"), tree("c")),
                tree("a", tree("x", tree("b"), tree("c"))), 1); // Adopt consecutive children.
        checkDistance(tree("a", tree("b"), tree("c")), tree("a", tree("c"), tree("b")), 2);
        rejectsBadReplayMapping();
        rejectsOverBudget();
        boolean rejected = false;
        try { RawAstTrace.trace(new RawAstTrace.Prepared(tree("a")), new RawAstTrace.Prepared(tree("b")), 0); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "incorrect authoritative cost fails closed");
        System.out.println("RawAstTraceSelfTest passed (" + pairs + " exhaustive pairs, " + checks + " checks)");
    }

    private static void checkDistance(RawAstTrace.Tree left, RawAstTrace.Tree right, int expected) {
        RawAstTrace.Prepared l = new RawAstTrace.Prepared(left), r = new RawAstTrace.Prepared(right);
        check(RawAstTrace.distance(l, r) == expected, "node promotion/adoption fixture");
        check(RawAstTrace.trace(l, r, expected).edits().size() == expected, "promotion/adoption replay");
    }

    private static List<RawAstTrace.Tree> trees(int size) {
        List<RawAstTrace.Tree> result = new ArrayList<>();
        for (List<RawAstTrace.Tree> children : forests(size - 1)) for (String label : List.of("a", "b"))
            result.add(new RawAstTrace.Tree(label, children, null));
        return result;
    }
    private static List<List<RawAstTrace.Tree>> forests(int size) {
        if (size == 0) return List.of(List.of());
        List<List<RawAstTrace.Tree>> result = new ArrayList<>();
        for (int first = 1; first <= size; first++)
            for (RawAstTrace.Tree root : trees(first)) for (List<RawAstTrace.Tree> tail : forests(size - first)) {
                List<RawAstTrace.Tree> list = new ArrayList<>(); list.add(root); list.addAll(tail); result.add(list);
            }
        return result;
    }

    private static final class Preorder {
        final List<String> labels = new ArrayList<>();
        final List<Integer> parents = new ArrayList<>();
        Preorder(RawAstTrace.Tree root) { append(root, -1); }
        void append(RawAstTrace.Tree node, int parent) {
            int index = labels.size(); labels.add(node.label); parents.add(parent);
            for (RawAstTrace.Tree child : node.children) append(child, index);
        }
        boolean ancestor(int first, int second) {
            for (int at = parents.get(second); at >= 0; at = parents.get(at)) if (at == first) return true;
            return false;
        }
    }
    private static int oracle(RawAstTrace.Tree left, RawAstTrace.Tree right) {
        Preorder l = new Preorder(left), r = new Preorder(right);
        int[] mapping = new int[l.labels.size()]; Arrays.fill(mapping, -1);
        return enumerate(l, r, mapping, 0, -1, l.labels.size() + r.labels.size());
    }
    private static int enumerate(Preorder left, Preorder right, int[] mapping, int at, int last, int cost) {
        if (at == mapping.length) return cost;
        mapping[at] = -1;
        int minimum = enumerate(left, right, mapping, at + 1, last, cost);
        for (int candidate = last + 1; candidate < right.labels.size(); candidate++) {
            boolean compatible = true;
            for (int previous = 0; previous < at; previous++) if (mapping[previous] >= 0
                    && left.ancestor(previous, at) != right.ancestor(mapping[previous], candidate)) compatible = false;
            if (!compatible) continue;
            mapping[at] = candidate;
            int delta = left.labels.get(at).equals(right.labels.get(candidate)) ? -2 : -1;
            minimum = Math.min(minimum, enumerate(left, right, mapping, at + 1, candidate, cost + delta));
        }
        mapping[at] = -1;
        return minimum;
    }

    private static void rejectsBadReplayMapping() throws Exception {
        RawAstTrace.Prepared p = new RawAstTrace.Prepared(tree("a", tree("b"), tree("c")));
        var field = RawAstTrace.Prepared.class.getDeclaredField("index"); field.setAccessible(true);
        Object index = field.get(p);
        var replay = RawAstTrace.class.getDeclaredMethod("replay", index.getClass(), index.getClass(), int[].class, int[].class);
        replay.setAccessible(true);
        for (int[] mapping : List.of(new int[] {0, 2, 1, 3}, new int[] {0, 1, 1, 3})) {
            boolean rejected = false;
            try { replay.invoke(null, index, index, mapping, mapping); }
            catch (InvocationTargetException error) { rejected = error.getCause() instanceof IllegalStateException; }
            check(rejected, "replay rejects reversed and noninjective mappings");
        }
    }
    private static void rejectsOverBudget() {
        List<RawAstTrace.Tree> children = new ArrayList<>();
        for (int i = 0; i < 999; i++) children.add(tree("a"));
        RawAstTrace.Prepared broad = new RawAstTrace.Prepared(new RawAstTrace.Tree("a", children, null));
        boolean rejected = false;
        try { RawAstTrace.distance(broad, broad); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "pair forest-work limit");
        for (int i = 0; i < 26; i++) children.add(tree("a"));
        rejected = false;
        try { new RawAstTrace.Prepared(new RawAstTrace.Tree("a", children, null)); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "tree node limit");
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
