package is.fivefivefive.CanDis.metric;

import java.util.List;
import org.json.JSONObject;

/** Adversarial checks of the narrow replay checker, not certificate authority. */
public final class MatrixReplayTest {
    private record N(String head, boolean unordered, List<N> children, List<N> alternatives) { }
    private static int checks;
    private static N leaf(String h) { return node(h, false); }
    private static N node(String h, boolean u, N... c) { return new N(h, u, List.of(c), List.of()); }
    private static final class Cost implements MatrixReplay.NativeCost<N> {
        boolean forbidDistance;
        boolean minimizeAbsentRoot;
        String bindingContext = "original";
        public String label(N n) { return n.head + ":" + n.unordered + ":" + bindingContext; }
        public List<N> children(N n) { return n.children; }
        public List<N> alternatives(N n) { return n.alternatives; }
        public boolean unordered(N a, N b) { return a.head.equals(b.head) && a.unordered && b.unordered; }
        public int size(N n) { return n == null ? 0 : 1 + n.children.stream().mapToInt(this::size).sum(); }
        public int update(N a, N b) { return a.head.equals(b.head) ? 0 : 1; }
        public int distance(N a, N b) {
            if (forbidDistance) throw new AssertionError("Verifier called native distance");
            if (a == null || b == null) {
                N present = a == null ? b : a;
                if (minimizeAbsentRoot && present != null && !present.alternatives.isEmpty())
                    return present.alternatives.stream().mapToInt(n -> distance(n,null)).min().orElseThrow();
                return size(present);
            }
            if (!a.alternatives.isEmpty() || !b.alternatives.isEmpty()) {
                List<N> as = a.alternatives.isEmpty() ? List.of(a) : a.alternatives;
                List<N> bs = b.alternatives.isEmpty() ? List.of(b) : b.alternatives;
                int best = Integer.MAX_VALUE;
                for (N x : as) for (N y : bs) best = Math.min(best, distance(x, y));
                return best;
            }
            return update(a,b) + (unordered(a,b) ? match(a.children,b.children,0,0)
                    : sequence(a.children,b.children,0,0));
        }
        private int match(List<N> a, List<N> b, int i, int used) {
            if (i == a.size()) {
                int cost = 0;
                for (int j = 0; j < b.size(); j++) if ((used & (1 << j)) == 0) cost += size(b.get(j));
                return cost;
            }
            int best = size(a.get(i)) + match(a,b,i+1,used);
            for (int j = 0; j < b.size(); j++) if ((used & (1 << j)) == 0)
                best = Math.min(best, distance(a.get(i),b.get(j)) + match(a,b,i+1,used | (1 << j)));
            return best;
        }
        private int sequence(List<N> a, List<N> b, int i, int j) {
            if (i == a.size()) return b.subList(j,b.size()).stream().mapToInt(this::size).sum();
            if (j == b.size()) return a.subList(i,a.size()).stream().mapToInt(this::size).sum();
            return Math.min(distance(a.get(i),b.get(j)) + sequence(a,b,i+1,j+1),
                    Math.min(size(a.get(i)) + sequence(a,b,i+1,j), size(b.get(j)) + sequence(a,b,i,j+1)));
        }
    }
    private static void check(boolean yes) { checks++; if (!yes) throw new AssertionError("check " + checks); }
    private static void reject(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Accepted invalid receipt at check " + checks);
    }
    private static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }

    public static void main(String[] args) {
        Cost cost = new Cost();
        N a = node("AND",true,leaf("a"),leaf("b"),leaf("a"));
        N b = node("AND",true,leaf("b"),leaf("a"));
        JSONObject receipt = new MatrixReplay<>(cost).capture(a,b);
        check(receipt.getInt("cost") == 1);
        MatrixReplay<N> reused = new MatrixReplay<>(cost);
        JSONObject mutable = reused.capture(a,b);
        mutable.getJSONArray("source").put(0,"tampered source");
        reject(() -> reused.verify(a,b,mutable));
        cost.forbidDistance = true;
        check(new MatrixReplay<>(cost).verify(a,b,receipt) == 1);
        cost.bindingContext = "substituted";
        reject(() -> new MatrixReplay<>(cost).verify(a,b,receipt));
        cost.bindingContext = "original";
        reject(() -> new MatrixReplay<>(cost).verify(node("AND",true,leaf("a"),leaf("c"),leaf("a")),b,receipt));
        reject(() -> new MatrixReplay<>(cost).verify(a,node("AND",true,leaf("c"),leaf("a")),receipt));
        JSONObject bad = copy(receipt);
        bad.getJSONObject("derivation").getJSONArray("children").getJSONObject(1).put("leftIndex",0);
        reject(() -> new MatrixReplay<>(cost).verify(a,b,bad));
        JSONObject charge = copy(receipt).put("cost",0);
        reject(() -> new MatrixReplay<>(cost).verify(a,b,charge));
        JSONObject overflow = copy(receipt).put("cost",4_294_967_297L);
        reject(() -> new MatrixReplay<>(cost).verify(a,b,overflow));
        JSONObject fractional = copy(receipt).put("cost",1.5);
        reject(() -> new MatrixReplay<>(cost).verify(a,b,fractional));
        JSONObject head = copy(receipt);
        head.getJSONObject("derivation").put("headCost",1);
        reject(() -> new MatrixReplay<>(cost).verify(a,b,head));
        cost.forbidDistance = false;
        N seqA = node("Seq",false,leaf("a"),leaf("b"));
        N seqB = node("Seq",false,leaf("b"),leaf("a"));
        JSONObject seq = new MatrixReplay<>(cost).capture(seqA,seqB);
        check(seq.getInt("cost") == 2);
        JSONObject reordered = copy(seq);
        reordered.getJSONObject("derivation").put("unordered",true);
        reject(() -> new MatrixReplay<>(cost).verify(seqA,seqB,reordered));
        N alt = new N("packed",false,List.of(),List.of(leaf("x"),leaf("a")));
        JSONObject selection = new MatrixReplay<>(cost).capture(alt,leaf("a"));
        check(selection.getInt("cost") == 0);
        selection.getJSONObject("derivation").put("leftAlternative",0);
        reject(() -> new MatrixReplay<>(cost).verify(alt,leaf("a"),selection));
        cost.minimizeAbsentRoot = true;
        N wholePhase = new N("packed",false,List.of(leaf("x"),leaf("y")),
                List.of(node("large",false,leaf("x")),leaf("small")));
        JSONObject removedPhase = new MatrixReplay<>(cost).capture(wholePhase,null);
        JSONObject addedPhase = new MatrixReplay<>(cost).capture(null,wholePhase);
        check(removedPhase.getInt("cost") == 1 && addedPhase.getInt("cost") == 1);
        cost.forbidDistance = true;
        check(new MatrixReplay<>(cost).verify(wholePhase,null,removedPhase) == 1);
        check(new MatrixReplay<>(cost).verify(null,wholePhase,addedPhase) == 1);
        JSONObject wrongAlternative = copy(removedPhase);
        wrongAlternative.getJSONObject("derivation").put("leftAlternative",0);
        reject(() -> new MatrixReplay<>(cost).verify(wholePhase,null,wrongAlternative));
        JSONObject wrongAbsent = copy(removedPhase);
        wrongAbsent.getJSONObject("derivation").put("rightAlternative",0);
        reject(() -> new MatrixReplay<>(cost).verify(wholePhase,null,wrongAbsent));
        cost.forbidDistance = false;
        N wrapped = node("parent",false,wholePhase), bare = node("parent",false);
        JSONObject rawChild = new MatrixReplay<>(cost).capture(wrapped,bare);
        check(rawChild.getInt("cost") == 3);
        JSONObject cheaperChild = copy(rawChild).put("cost",1).put("replayedCost",1);
        cheaperChild.getJSONObject("derivation").put("cost",1).getJSONArray("children")
                .getJSONObject(0).put("edit",copy(removedPhase.getJSONObject("derivation")));
        cost.forbidDistance = true;
        reject(() -> new MatrixReplay<>(cost).verify(wrapped,bare,cheaperChild));
        cost.forbidDistance = false;
        reject(() -> new MatrixReplay<>(cost,1).capture(a,b));
        N dag = leaf("x");
        for (int i = 0; i < 40; i++) dag = node("DAG",false,dag,dag);
        N exponential = dag;
        // No native distance call or expanded serialization may precede rejection.
        cost.forbidDistance = true;
        reject(() -> new MatrixReplay<>(cost,50_000).capture(exponential,exponential));
        cost.forbidDistance = false;
        N many = node("AND",true,leaf("a"),leaf("b"),leaf("c"),leaf("d"),leaf("e"),
                leaf("f"),leaf("g"),leaf("h"),leaf("i"));
        // A trivial native cost avoids invoking the exponential reference matcher.
        MatrixReplay.NativeCost<N> large = new MatrixReplay.NativeCost<>() {
            public String label(N n) { return cost.label(n); }
            public List<N> children(N n) { return n.children; }
            public List<N> alternatives(N n) { return List.of(); }
            public boolean unordered(N x,N y) { return true; }
            public int size(N n) { return cost.size(n); }
            public int update(N x,N y) { return 0; }
            public int distance(N x,N y) { return 0; }
        };
        JSONObject wide = new MatrixReplay<>(large).capture(many,many);
        check(new MatrixReplay<>(large).verify(many,many,wide) == 0);
        N overBudget = new N("AND",true,java.util.Collections.nCopies(65,leaf("a")),List.of());
        reject(() -> new MatrixReplay<>(large).capture(overBudget,overBudget));
        System.out.println("MatrixReplayTest: " + checks + " checks passed");
    }
}
