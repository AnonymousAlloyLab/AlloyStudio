package is.fivefivefive.CanDis;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;
import is.fivefivefive.CanDis.theory.StructuralKey;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;

/** Cost, tie, collision and request-local snapshot controls for performance changes. */
public final class PerformanceRegressionTest {
    private static int checks;
    private record Tree(String label, List<Tree> children) { }
    private static final class CountingAdapter implements OrderedTreeEditDistance.Adapter<Tree> {
        int labels;
        public String label(Tree node) { labels++; return node.label(); }
        public List<Tree> children(Tree node) { return node.children(); }
    }

    public static void main(String[] args) throws Exception {
        boolean probe = args.length > 0 && args[0].equals("--probe");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Random random = new Random(4072);
        List<Tree> trees = new ArrayList<>();
        for (int i = 0; i < 45; i++) trees.add(tree(random, 3));
        long start = System.nanoTime();
        long labelCalls = 0;
        for (Tree left : trees) for (Tree right : trees) {
            CountingAdapter adapter = new CountingAdapter();
            int distance = OrderedTreeEditDistance.distance(left, right, adapter);
            int expectedCalls = size(left) + size(right);
            labelCalls += adapter.labels;
            if (!probe) check(adapter.labels == expectedCalls, "one label per occurrence in numeric distance");
            adapter.labels = 0;
            var alignment = OrderedTreeEditDistance.alignment(left, right, adapter);
            if (!probe) check(adapter.labels == expectedCalls, "one label per occurrence in alignment");
            check(alignment.cost() == distance, "numeric and selected alignment costs agree");
            check(OrderedTreeEditDistance.distance(right, left, adapter) == distance, "both directions");
            digest.update((distance + ":" + alignment.matches() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("ordered ms=" + (System.nanoTime() - start) / 1e6 + " numericLabelCalls=" + labelCalls);
        Tree leaf = new Tree("a", List.of());
        check(OrderedTreeEditDistance.distance(new Tree("b", List.of(leaf)), leaf,
                new CountingAdapter()) == 1, "deletion promotes children, not subtree deletion");
        List<Tree> mutableChildren = new ArrayList<>();
        Tree mutableTree = new Tree("a", mutableChildren);
        check(OrderedTreeEditDistance.distance(mutableTree, leaf, new CountingAdapter()) == 0,
                "initial request snapshot");
        mutableChildren.add(new Tree("b", List.of()));
        check(OrderedTreeEditDistance.distance(mutableTree, leaf, new CountingAdapter()) == 1,
                "later request sees changed input; no global cache");
        boolean rejected = false;
        try { OrderedTreeEditDistance.distance(new Tree(null, List.of()), leaf, new CountingAdapter()); }
        catch (NullPointerException expected) { rejected = true; }
        check(rejected, "null label rejected during indexing");

        var model = new ModelUnit(null, CompUtil.parseEverything_fromString(A4Reporter.NOP, """
                sig A { r: set A }
                pred p0 { some A }
                pred p1 { no A }
                pred p2 { all x:A | some x.r }
                pred p3 { some x:A | all y:x.r | y in A }
                pred p4 { let x = A.r | some (x + A) }
                pred p5 { always (some A implies after no r) }
                pred p6 { #A > 1 and lone r }
                pred p7 { some ^r and some ~r }
                pred p8 { p0[] or p1[] }
                """));
        Method batch = CanonicalBatchTest.class.getDeclaredMethod("rawAstTreeDistance", Node.class, Node.class);
        batch.setAccessible(true);
        List<Node> nodes = new ArrayList<>();
        nodes.add(null);
        for (var predicate : model.getPredDeclList()) nodes.add(predicate.getBody());
        start = System.nanoTime();
        for (Node left : nodes) for (Node right : nodes) {
            int expected = originalDistance(left, right);
            check((int) batch.invoke(null, left, right) == expected, "batch original recurrence incl. null/wrappers");
            digest.update((expected + "\n").getBytes(StandardCharsets.UTF_8));
        }
        for (int repeat = 0; repeat < 30; repeat++)
            for (Node left : nodes) for (Node right : nodes) batch.invoke(null, left, right);
        System.out.println("batch ms=" + (System.nanoTime() - start) / 1e6);

        StructuralKey aa = StructuralKey.leaf("tag", "Aa"), bb = StructuralKey.leaf("tag", "BB");
        check(aa.hashCode() == bb.hashCode() && !aa.equals(bb), "hash collision never establishes equality");
        List<StructuralKey> keys = new ArrayList<>(List.of(aa, bb));
        for (int i = 0; i < 60; i++) {
            StructuralKey child = keys.get(random.nextInt(keys.size()));
            keys.add(StructuralKey.of("tag" + random.nextInt(3), List.of("s" + random.nextInt(5)), List.of(child, child)));
        }
        for (StructuralKey left : keys) for (StructuralKey right : keys) {
            StructuralKey copy = StructuralKey.of(right.tag(), right.scalars(), right.children());
            check(left.equals(right) == left.stableString().equals(right.stableString()), "equality matches full encoding");
            check(left.compareTo(right) == originalCompare(left, right), "unchanged total order");
            check(copy.equals(right) && copy.hashCode() == right.hashCode(), "independent equal key");
            digest.update((left.compareTo(right) + ":" + left.equals(right) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        List<String> mutable = new ArrayList<>(List.of("before"));
        StructuralKey immutable = StructuralKey.of("copy", mutable, List.of());
        mutable.set(0, "after");
        check(immutable.equals(StructuralKey.leaf("copy", "before")), "defensive scalar copy preserves cached hash");
        String resultDigest = HexFormat.of().formatHex(digest.digest());
        check(resultDigest.equals("0af040ac2f4a4686b17fed81bfeb514357e641871074e28c7ccabaf0c4dd35f2"),
                "frozen pre-optimization cost, selected-tie and key-order digest");
        System.out.println("resultDigest=" + resultDigest);
        System.out.println("PerformanceRegressionTest: " + checks + " checks passed");
    }

    private static Tree tree(Random random, int depth) {
        List<Tree> children = new ArrayList<>();
        if (depth > 0) for (int i = random.nextInt(4); i > 0; i--) children.add(tree(random, depth - 1));
        return new Tree("n" + random.nextInt(3), List.copyOf(children));
    }

    private static int size(Tree tree) {
        int result = 1;
        for (Tree child : tree.children()) result += size(child);
        return result;
    }

    private static int originalSize(Node node) {
        if (node == null) return 0;
        int result = 1;
        for (Node child : DatasetConventions.rawAstChildren(node)) result += originalSize(child);
        return result;
    }

    private static int originalDistance(Node left, Node right) {
        if (left == null) return originalSize(right);
        if (right == null) return originalSize(left);
        List<Node> a = DatasetConventions.rawAstChildren(left), b = DatasetConventions.rawAstChildren(right);
        int[][] dp = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) dp[i][0] = dp[i - 1][0] + originalSize(a.get(i - 1));
        for (int j = 1; j <= b.size(); j++) dp[0][j] = dp[0][j - 1] + originalSize(b.get(j - 1));
        for (int i = 1; i <= a.size(); i++) for (int j = 1; j <= b.size(); j++) {
            int replace = dp[i - 1][j - 1] + originalDistance(a.get(i - 1), b.get(j - 1));
            dp[i][j] = Math.min(replace, Math.min(dp[i - 1][j] + originalSize(a.get(i - 1)),
                    dp[i][j - 1] + originalSize(b.get(j - 1))));
        }
        return dp[a.size()][b.size()] + (DatasetConventions.rawAstLabel(left).equals(DatasetConventions.rawAstLabel(right)) ? 0 : 1);
    }

    private static int originalCompare(StructuralKey left, StructuralKey right) {
        int result = left.tag().compareTo(right.tag());
        if (result != 0) return result;
        for (int i = 0; i < Math.min(left.scalars().size(), right.scalars().size()); i++) {
            result = left.scalars().get(i).compareTo(right.scalars().get(i));
            if (result != 0) return result;
        }
        result = Integer.compare(left.scalars().size(), right.scalars().size());
        if (result != 0) return result;
        for (int i = 0; i < Math.min(left.children().size(), right.children().size()); i++) {
            result = originalCompare(left.children().get(i), right.children().get(i));
            if (result != 0) return result;
        }
        return Integer.compare(left.children().size(), right.children().size());
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
