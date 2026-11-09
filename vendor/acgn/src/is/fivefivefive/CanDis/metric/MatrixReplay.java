package is.fivefivefive.CanDis.metric;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Diagnostic matrix derivations at fixed native bindings, not public repair plans. */
public final class MatrixReplay<N> {
    public interface NativeCost<N> {
        /** Complete immutable head identity, including the selected binding context. */
        String label(N node);
        /** Source/type/owner evidence ignored by head cost but retained in executable payloads. */
        default String evidence(N node) { return label(node); }
        /** Actual-object provenance, separate from payload equality and edit classification. */
        default Object sourceObjectEvidence(N node) { return JSONObject.NULL; }
        List<N> children(N node);
        List<N> alternatives(N node);
        boolean unordered(N left, N right);
        int size(N node);
        int update(N left, N right);
        int distance(N left, N right);
    }

    private final NativeCost<N> nativeCost;
    private final IdentityHashMap<N, JSONArray> snapshots = new IdentityHashMap<>();
    private final IdentityHashMap<N, String> hashes = new IdentityHashMap<>();
    private final IdentityHashMap<N, Long> encodedLengths = new IdentityHashMap<>();
    private long remaining;

    public MatrixReplay(NativeCost<N> nativeCost) { this(nativeCost, 1_000_000); }

    public MatrixReplay(NativeCost<N> nativeCost, long budget) {
        if (budget <= 0) throw new IllegalArgumentException("Replay budget must be positive");
        this.nativeCost = java.util.Objects.requireNonNull(nativeCost);
        remaining = budget;
    }

    private void spend(long amount) {
        if (amount < 0 || amount > remaining) throw new IllegalStateException("MATRIX_REPLAY_BUDGET");
        remaining -= amount;
    }

    private void enter(int depth) {
        spend(1);
    }

    private JSONArray snapshot(N node, int depth) {
        enter(depth);
        if (node == null) return new JSONArray();
        JSONArray known = snapshots.get(node);
        if (known != null) return known;
        String label = nativeCost.label(node);
        spend(label.length());
        // JSONArray serializes shared references by value. Charge the expanded
        // length for every occurrence BEFORE putting it in a parent or encoding it.
        String encodedLabel = JSONObject.quote(label);
        long length = Math.addExact(encodedLabel.length(), 8L); // [label,[],[]]
        spend(length);
        JSONArray children = new JSONArray();
        for (N child : nativeCost.children(node)) {
            JSONArray value = snapshot(child, depth + 1);
            long added = Math.addExact(child == null ? 2L : encodedLengths.get(child), children.isEmpty() ? 0L : 1L);
            spend(added); length = Math.addExact(length, added); children.put(value);
        }
        JSONArray alternatives = new JSONArray();
        for (N alternative : nativeCost.alternatives(node)) {
            JSONArray value = snapshot(alternative, depth + 1);
            long added = Math.addExact(alternative == null ? 2L : encodedLengths.get(alternative), alternatives.isEmpty() ? 0L : 1L);
            spend(added); length = Math.addExact(length, added); alternatives.put(value);
        }
        JSONArray result = new JSONArray().put(label).put(children).put(alternatives);
        String encoded = result.toString();
        require(encoded.length() == length, "Snapshot encoded-length accounting");
        snapshots.put(node, result);
        encodedLengths.put(node, length);
        hashes.put(node, sha256(encoded));
        return result;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private String hash(N node) { return node == null ? sha256("[]") : hashes.get(node); }

    public JSONObject capture(N left, N right) {
        JSONArray source = snapshot(left, 0), target = snapshot(right, 0);
        JSONObject derivation = traceRoot(left, right, 0);
        int expected = nativeCost.distance(left, right);
        int replayed = verifyRoot(left, right, derivation, 0);
        require(expected == replayed, "Native matrix cost disagrees with replay");
        // Do not expose cached snapshots: callers may edit their receipt before replay.
        return new JSONObject().put("source", new JSONArray(source.toString()))
                .put("target", new JSONArray(target.toString()))
                .put("sourceSha256", hash(left)).put("targetSha256", hash(right))
                .put("cost", expected).put("replayedCost", replayed)
                .put("matrixReplayChecked", true).put("derivation", derivation);
    }

    /** Serializes the actual metric decision; cannot call distance or choose ties. */
    public JSONObject capture(MatrixDecision<N> choice) {
        N left=choice.source(),right=choice.target();
        JSONArray source=snapshot(left,0),target=snapshot(right,0);
        JSONObject derivation=retained(choice,0);
        int replayed=verifyRoot(left,right,derivation,0);
        require(replayed==choice.cost(),"Retained decision/native charge mismatch");
        return new JSONObject().put("source",new JSONArray(source.toString())).put("target",new JSONArray(target.toString()))
                .put("sourceSha256",hash(left)).put("targetSha256",hash(right)).put("cost",choice.cost())
                .put("replayedCost",replayed).put("matrixReplayChecked",true).put("derivation",derivation);
    }

    private JSONObject retained(MatrixDecision<N> choice,int depth) {
        enter(depth);var out=base(choice.source(),choice.target(),choice.kind(),choice.cost());
        if(choice.kind().equals("select"))return out.put("leftAlternative",choice.leftAlternative())
                .put("rightAlternative",choice.rightAlternative()).put("selected",retained(choice.selected(),depth+1));
        if(choice.kind().equals("node")){
            var edges=new JSONArray();for(var e:choice.children())edges.put(new JSONObject().put("leftIndex",e.leftIndex())
                    .put("rightIndex",e.rightIndex()).put("edit",retained(e.decision(),depth+1)));
            out.put("unordered",choice.unordered()).put("headCost",choice.headCost()).put("children",edges);
        }
        return out;
    }

    /** Does not call NativeCost.distance or solve an assignment/sequence DP. */
    public int verify(N left, N right, JSONObject receipt) {
        require(snapshot(left, 0).similar(receipt.getJSONArray("source")), "Wrong source snapshot");
        require(snapshot(right, 0).similar(receipt.getJSONArray("target")), "Wrong target snapshot");
        require(hash(left).equals(receipt.getString("sourceSha256")), "Wrong source hash");
        require(hash(right).equals(receipt.getString("targetSha256")), "Wrong target hash");
        int result = verifyRoot(left, right, receipt.getJSONObject("derivation"), 0);
        require(result == integer(receipt, "cost") && result == integer(receipt, "replayedCost"),
                "Wrong receipt charge");
        return result;
    }

    /** Expands the already-selected native derivation into executable occurrence CRUD. */
    public JSONObject executable(N left, N right, JSONObject receipt) {
        verify(left, right, receipt);
        JSONObject program = MatrixProgram.capture(executionReceipt(left,right,receipt));
        return program.put("sourceObjects", sourceObjects(left,right,program));
    }

    /** Native premises are rechecked, then the detached store is executed without search. */
    public int verifyExecutable(N left, N right, JSONObject receipt, JSONObject program) {
        int cost = verify(left, right, receipt);
        require(MatrixProgram.verify(executionReceipt(left,right,receipt), program) == cost, "Executable matrix cost");
        require(sourceObjects(left,right,program).similar(program.getJSONArray("sourceObjects")),
                "Selected source object provenance");
        return cost;
    }

    private JSONArray sourceObjects(N left,N right,JSONObject program) {
        JSONArray result=new JSONArray(),operations=program.getJSONArray("operations");
        for(int i=0;i<operations.length();i++) {
            JSONObject operation=operations.getJSONObject(i);
            result.put(new JSONObject().put("operation",i)
                    .put("before",sourceObject(left,operation,"source"))
                    .put("after",sourceObject(right,operation,"target")));
        }
        return result;
    }

    private Object sourceObject(N root,JSONObject operation,String side) {
        if(operation.isNull(side))return JSONObject.NULL;
        String[] path=operation.getString(side).split("/");
        require(path.length%2==1&&path[0].equals("root"),"Source object address");
        N node=root;
        for(int i=1;i<path.length;i+=2) {
            spend(1);
            require(node!=null,"Absent source object");
            require(path[i].equals("child")||path[i].equals("alternative"),"Source object edge");
            List<N> edges=path[i].equals("child")?nativeCost.children(node):nativeCost.alternatives(node);
            node=edges.get(Integer.parseInt(path[i+1]));
        }
        require(node!=null,"Absent selected source object");
        Object evidence=nativeCost.sourceObjectEvidence(node);
        spend(evidence.toString().length());
        return evidence;
    }

    private JSONObject executionReceipt(N left,N right,JSONObject receipt) {
        JSONObject execution=new JSONObject(receipt.toString());
        var budget=new TraceResources();
        execution.put("source",executionSnapshot(left,budget)).put("target",executionSnapshot(right,budget));
        return execution;
    }

    private JSONArray executionSnapshot(N node,TraceResources budget) {
        if(node==null)return new JSONArray();
        String label=nativeCost.label(node),evidence=nativeCost.evidence(node);
        budget.spend(Math.addExact(64L,Math.multiplyExact(2L,(long)label.length()+evidence.length())));
        JSONArray children=new JSONArray(),alternatives=new JSONArray();
        for(N child:nativeCost.children(node))children.put(executionSnapshot(child,budget));
        for(N child:nativeCost.alternatives(node))alternatives.put(executionSnapshot(child,budget));
        return new JSONArray().put(label).put(children).put(alternatives).put(evidence);
    }

    private JSONObject traceRoot(N left, N right, int depth) {
        enter(depth);
        N present = left == null ? right : right == null ? left : null;
        if (present == null || nativeCost.alternatives(present).isEmpty()) return trace(left,right,depth);
        int expected = nativeCost.distance(left,right);
        List<N> alternatives = nativeCost.alternatives(present);
        for (int i = 0; i < alternatives.size(); i++) {
            N a = left == null ? null : alternatives.get(i);
            N b = right == null ? null : alternatives.get(i);
            if (nativeCost.distance(a,b) == expected) return base(left,right,"select",expected)
                    .put("leftAlternative",left == null ? -1 : i)
                    .put("rightAlternative",right == null ? -1 : i)
                    .put("selected",traceRoot(a,b,depth+1));
        }
        throw new IllegalStateException("MATRIX_REPLAY_NULL_ALTERNATIVE_ROOT");
    }

    private int verifyRoot(N left,N right,JSONObject proof,int depth) {
        enter(depth);
        if ((left != null && right != null) || !proof.getString("kind").equals("select"))
            return verifyNode(left,right,proof,depth);
        require(hash(left).equals(proof.getString("sourceSha256"))
                && hash(right).equals(proof.getString("targetSha256")),"Substituted phase occurrence");
        require((left == null ? "" : nativeCost.label(left)).equals(proof.getString("sourceHead"))
                && (right == null ? "" : nativeCost.label(right)).equals(proof.getString("targetHead")),
                "Substituted phase head");
        N a = select(left,integer(proof,"leftAlternative"));
        N b = select(right,integer(proof,"rightAlternative"));
        require(a != left || b != right,"Empty phase alternative selection");
        int cost = verifyRoot(a,b,proof.getJSONObject("selected"),depth+1);
        require(cost == integer(proof,"cost"),"Wrong phase selection charge");
        return cost;
    }

    private JSONObject base(N left, N right, String kind, int cost) {
        return new JSONObject().put("kind", kind).put("cost", cost)
                .put("sourceSha256", hash(left)).put("targetSha256", hash(right))
                .put("sourceHead", left == null ? "" : nativeCost.label(left))
                .put("targetHead", right == null ? "" : nativeCost.label(right));
    }

    private JSONObject trace(N left, N right, int depth) {
        enter(depth);
        if (left == null || right == null) {
            N present = left == null ? right : left;
            // Whole-phase null roots with alternative-minimum charging need a
            // different selection receipt. Child deletion always charges raw size.
            if (present != null && nativeCost.distance(left, right) != nativeCost.size(present))
                throw new IllegalStateException("MATRIX_REPLAY_NULL_ALTERNATIVE_ROOT");
            return base(left, right, left == null ? "insert" : "delete",
                    present == null ? 0 : nativeCost.size(present));
        }
        int cost = nativeCost.distance(left, right);
        List<N> la = nativeCost.alternatives(left), ra = nativeCost.alternatives(right);
        if (!la.isEmpty() || !ra.isEmpty()) {
            List<N> ls = la.isEmpty() ? List.of(left) : la;
            List<N> rs = ra.isEmpty() ? List.of(right) : ra;
            for (int i = 0; i < ls.size(); i++) for (int j = 0; j < rs.size(); j++) {
                spend(1);
                if (nativeCost.distance(ls.get(i), rs.get(j)) == cost)
                    return base(left, right, "select", cost)
                            .put("leftAlternative", la.isEmpty() ? -1 : i)
                            .put("rightAlternative", ra.isEmpty() ? -1 : j)
                            .put("selected", trace(ls.get(i), rs.get(j), depth + 1));
            }
            throw new IllegalStateException("No native alternative attains cost");
        }
        List<N> ls = nativeCost.children(left), rs = nativeCost.children(right);
        boolean unordered = nativeCost.unordered(left, right);
        List<int[]> pairs = unordered ? assignment(ls, rs) : sequence(ls, rs);
        JSONArray edges = new JSONArray();
        int charge = nativeCost.update(left, right);
        for (int[] pair : pairs) {
            int i = pair[0], j = pair[1];
            N l = i < 0 ? null : ls.get(i), r = j < 0 ? null : rs.get(j);
            JSONObject child;
            if (i < 0 || j < 0) {
                child = base(l, r, i < 0 ? "insert" : "delete", nativeCost.size(i < 0 ? r : l));
            } else child = trace(l, r, depth + 1);
            charge = Math.addExact(charge, child.getInt("cost"));
            edges.put(new JSONObject().put("leftIndex", i).put("rightIndex", j).put("edit", child));
        }
        require(charge == cost, "Backtrace does not attain native cost");
        return base(left, right, "node", cost).put("unordered", unordered)
                .put("headCost", nativeCost.update(left, right)).put("children", edges);
    }

    private List<int[]> sequence(List<N> left, List<N> right) {
        int n = left.size(), m = right.size();
        spend(Math.multiplyExact((long)n + 1, (long)m + 1));
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) dp[i][0] = Math.addExact(dp[i-1][0], nativeCost.size(left.get(i-1)));
        for (int j = 1; j <= m; j++) dp[0][j] = Math.addExact(dp[0][j-1], nativeCost.size(right.get(j-1)));
        for (int i = 1; i <= n; i++) for (int j = 1; j <= m; j++)
            dp[i][j] = Math.min(Math.addExact(dp[i-1][j-1], nativeCost.distance(left.get(i-1), right.get(j-1))),
                    Math.min(Math.addExact(dp[i-1][j], nativeCost.size(left.get(i-1))),
                            Math.addExact(dp[i][j-1], nativeCost.size(right.get(j-1)))));
        List<int[]> pairs = new ArrayList<>();
        int i = n, j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && dp[i][j] == Math.addExact(dp[i-1][j-1], nativeCost.distance(left.get(i-1), right.get(j-1)))) {
                pairs.add(new int[]{--i, --j});
            } else if (i > 0 && dp[i][j] == Math.addExact(dp[i-1][j], nativeCost.size(left.get(i-1)))) {
                pairs.add(new int[]{--i, -1});
            } else pairs.add(new int[]{-1, --j});
        }
        java.util.Collections.reverse(pairs);
        return pairs;
    }

    private List<int[]> assignment(List<N> left, List<N> right) {
        int n = left.size(), m = right.size(), dimension = Math.addExact(n, m);
        if (n == 0 || m == 0) return sequence(left, right);
        spend(Math.multiplyExact(Math.multiplyExact((long)dimension, dimension),dimension));
        int[][] costs = new int[dimension][dimension];
        for (int i = 0; i < dimension; i++) for (int j = 0; j < dimension; j++)
            costs[i][j] = i < n && j < m ? nativeCost.distance(left.get(i), right.get(j))
                    : i < n ? nativeCost.size(left.get(i)) : j < m ? nativeCost.size(right.get(j)) : 0;
        int[] columns = QuotientRepairDistance.minimumAssignmentColumns(costs);
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < dimension; i++) {
            int j = columns[i];
            if (i < n || j < m) pairs.add(new int[]{i < n ? i : -1, j < m ? j : -1});
        }
        return pairs;
    }

    private int verifyNode(N left, N right, JSONObject proof, int depth) {
        enter(depth);
        require(hash(left).equals(proof.getString("sourceSha256"))
                && hash(right).equals(proof.getString("targetSha256")), "Substituted occurrence");
        require((left == null ? "" : nativeCost.label(left)).equals(proof.getString("sourceHead"))
                && (right == null ? "" : nativeCost.label(right)).equals(proof.getString("targetHead")), "Substituted head");
        String kind = proof.getString("kind");
        int cost;
        if (left == null || right == null) {
            require(kind.equals(left == null ? "insert" : "delete"), "Wrong whole-subtree edit");
            cost = left == null && right == null ? 0 : nativeCost.size(left == null ? right : left);
        } else if (kind.equals("select")) {
            N l = select(left, integer(proof, "leftAlternative"));
            N r = select(right, integer(proof, "rightAlternative"));
            require(l != left || r != right, "Empty alternative selection");
            cost = verifyNode(l, r, proof.getJSONObject("selected"), depth + 1);
        } else {
            require(kind.equals("node") && nativeCost.alternatives(left).isEmpty()
                    && nativeCost.alternatives(right).isEmpty(), "Missing alternative selection");
            boolean unordered = nativeCost.unordered(left, right);
            require(proof.getBoolean("unordered") == unordered, "Changed sequence semantics");
            cost = nativeCost.update(left, right);
            require(cost == integer(proof, "headCost"), "Wrong head charge/binding");
            List<N> ls = nativeCost.children(left), rs = nativeCost.children(right);
            boolean[] usedLeft = new boolean[ls.size()], usedRight = new boolean[rs.size()];
            int priorLeft = -1, priorRight = -1;
            JSONArray children = proof.getJSONArray("children");
            for (int k = 0; k < children.length(); k++) {
                JSONObject edge = children.getJSONObject(k);
                int i = integer(edge, "leftIndex"), j = integer(edge, "rightIndex");
                require(i >= -1 && j >= -1 && (i >= 0 || j >= 0), "Invalid child indices");
                require(i < ls.size() && j < rs.size(), "Child index out of bounds");
                if (i >= 0) {
                    require(!usedLeft[i] && (unordered || i > priorLeft), "Source multiplicity/order violation");
                    usedLeft[i] = true; priorLeft = i;
                }
                if (j >= 0) {
                    require(!usedRight[j] && (unordered || j > priorRight), "Target multiplicity/order violation");
                    usedRight[j] = true; priorRight = j;
                }
                cost = Math.addExact(cost, verifyNode(i < 0 ? null : ls.get(i), j < 0 ? null : rs.get(j),
                        edge.getJSONObject("edit"), depth + 1));
            }
            for (boolean used : usedLeft) require(used, "Unconsumed source occurrence");
            for (boolean used : usedRight) require(used, "Unproduced target occurrence");
        }
        require(cost == integer(proof, "cost"), "Wrong derivation charge");
        return cost;
    }

    private N select(N node, int index) {
        if (node == null) { require(index == -1,"Selection from absent phase"); return null; }
        List<N> alternatives = nativeCost.alternatives(node);
        if (alternatives.isEmpty()) { require(index == -1, "Spurious selection"); return node; }
        require(index >= 0 && index < alternatives.size(), "Invalid alternative index");
        return alternatives.get(index);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("MATRIX_REPLAY_INVALID: " + message);
    }

    private static int integer(JSONObject value, String key) {
        Object number = value.get(key);
        require(number instanceof Number, "Non-numeric " + key);
        try { return new java.math.BigDecimal(number.toString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("MATRIX_REPLAY_INVALID: Non-integral/out-of-range " + key, ex);
        }
    }
}
