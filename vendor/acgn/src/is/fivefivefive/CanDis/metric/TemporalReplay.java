package is.fivefivefive.CanDis.metric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;

/** Executable node edits on the native ordered temporal tree, not source rewrites. */
public final class TemporalReplay {
    private TemporalReplay() { }

    private record Tree(String label, List<Tree> children) { }
    private static final OrderedTreeEditDistance.Adapter<Tree> ADAPTER =
            new OrderedTreeEditDistance.Adapter<>() {
                public String label(Tree n) { return n.label; }
                public List<Tree> children(Tree n) { return n.children; }
            };

    public static <N> JSONObject capture(N left, N right, int nativeCost,
            Function<N, String> label, Function<N, List<N>> children) {
        return capture(left,right,nativeCost,label,children,null);
    }

    /** Uses the alignment retained by numeric evaluation, without solving again. */
    public static <N> JSONObject capture(N left,N right,int nativeCost,Function<N,String> label,
            Function<N,List<N>> children,OrderedTreeEditDistance.Alignment selected) {
        Tree a = snapshot(left, label, children, new TraceResources());
        Tree b = snapshot(right, label, children, new TraceResources());
        var alignment = selected==null?OrderedTreeEditDistance.alignment(a,b,ADAPTER):selected;
        require(alignment.cost() == nativeCost, "Temporal native cost mismatch");
        List<Tree> bs = new ArrayList<>();
        postorder(b, bs);
        Map<Integer, Integer> matches = new HashMap<>();
        for (var match : alignment.matches())
            require(matches.put(match.leftPostorder(), match.rightPostorder()) == null,
                    "Repeated temporal source match");
        List<Node> sourceNodes = new ArrayList<>();
        Node forest = forest(a, sourceNodes);
        Map<Tree, Node> targetNodes = new java.util.IdentityHashMap<>();
        JSONArray actions = new JSONArray();
        for (int i = 0; i < sourceNodes.size(); i++) {
            Node node = sourceNodes.get(i);
            Integer target = matches.get(i + 1);
            if (target == null) {
                JSONObject edit = edit("delete", path(node)).put("label", node.label)
                        .put("childCount", node.children.size());
                apply(forest, edit); actions.put(edit);
            } else {
                Tree destination = bs.get(target - 1);
                require(targetNodes.put(destination, node) == null, "Repeated temporal target match");
                if (!node.label.equals(destination.label)) {
                    JSONObject edit = edit("update", path(node)).put("from", node.label)
                            .put("to", destination.label);
                    apply(forest, edit); actions.put(edit);
                }
            }
        }
        // After deletions, retained matches form the target's pruned skeleton.
        // Preorder insertion wraps its contiguous child ranges, including empty ranges.
        List<Tree> preorder = new ArrayList<>();
        preorder(b, preorder);
        Map<Tree, Integer> order = new java.util.IdentityHashMap<>();
        for (int i = 0; i < preorder.size(); i++) order.put(preorder.get(i), i);
        Map<Node, Tree> targets = new java.util.IdentityHashMap<>();
        targetNodes.forEach((tree, node) -> targets.put(node, tree));
        insertMissing(b, forest, targetNodes, targets, order, actions, forest);
        require(forest.children.size() == 1 && json(forest.children.get(0)).similar(json(b)),
                "Temporal generated endpoint mismatch");
        JSONObject result = new JSONObject().put("schema", "acgn-temporal-replay-v1")
                .put("source", json(a)).put("target", json(b)).put("operations", actions)
                .put("cost", nativeCost).put("replayedCost", actions.length())
                .put("complete", false).put("publicSequenceLinked", false);
        var correspondence=new JSONArray();var reads=new JSONArray();var as=new ArrayList<Tree>();postorder(a,as);
        for(var match:alignment.matches()) {
            correspondence.put(new JSONArray().put(match.leftPostorder()).put(match.rightPostorder()));
            String before=as.get(match.leftPostorder()-1).label,after=bs.get(match.rightPostorder()-1).label;
            if(before.equals(after))reads.put(new JSONObject().put("kind","read-keep").put("sourcePostorder",match.leftPostorder())
                    .put("targetPostorder",match.rightPostorder()).put("before",before).put("after",after).put("cost",0));
        }
        result.put("selectedCorrespondence",correspondence).put("zeroCostReads",reads);
        verify(left, right, label, children, result);
        return result.put("sliceReplayChecked", true);
    }

    private static void insertMissing(Tree target, Node parent, Map<Tree, Node> nodes,
            Map<Node, Tree> targets, Map<Tree, Integer> order, JSONArray actions, Node forest) {
        Node node = nodes.get(target);
        if (node == null) {
            int startOrder = order.get(target), endOrder = subtreeEnd(target, order);
            int start = 0;
            while (start < parent.children.size()
                    && order.get(targets.get(parent.children.get(start))) < startOrder) start++;
            int count = 0;
            while (start + count < parent.children.size()
                    && order.get(targets.get(parent.children.get(start + count))) <= endOrder) count++;
            JSONObject edit = edit("insert", path(parent)).put("label", target.label)
                    .put("index", start).put("childCount", count);
            apply(forest, edit); actions.put(edit);
            node = parent.children.get(start);
            nodes.put(target, node); targets.put(node, target);
        }
        require(node.parent == parent, "Temporal match violates target ancestry");
        for (Tree child : target.children) insertMissing(child, node, nodes, targets, order, actions, forest);
    }

    private static int subtreeEnd(Tree tree, Map<Tree, Integer> order) {
        while (!tree.children.isEmpty()) tree = tree.children.get(tree.children.size() - 1);
        return order.get(tree);
    }

    /** Replays only the supplied edits. No distance, alignment, or DP is used here. */
    public static <N> int verify(N left, N right, Function<N, String> label,
            Function<N, List<N>> children, JSONObject receipt) {
        Tree a = snapshot(left, label, children, new TraceResources());
        Tree b = snapshot(right, label, children, new TraceResources());
        require("acgn-temporal-replay-v1".equals(receipt.getString("schema")), "Temporal schema");
        require(json(a).similar(receipt.getJSONArray("source"))
                && json(b).similar(receipt.getJSONArray("target")), "Temporal receipt endpoints");
        JSONArray edits = receipt.getJSONArray("operations");
        require(edits.length() <= Math.addExact(size(a),size(b)), "Temporal operation coverage");
        Node forest = forest(a, new ArrayList<>());
        int cost = 0;
        for (int i = 0; i < edits.length(); i++) {
            apply(forest, edits.getJSONObject(i));
            cost = Math.addExact(cost, 1);
        }
        require(forest.children.size() == 1 && json(forest.children.get(0)).similar(json(b)),
                "Temporal replay target mismatch");
        if(receipt.has("selectedCorrespondence")) {
            var actual=new ArrayList<Node>();mutablePostorder(forest.children.get(0),actual);
            var sourceNodes=new ArrayList<Tree>();postorder(a,sourceNodes);
            var pairs=new ArrayList<int[]>();var reads=new ArrayList<JSONObject>();
            for(int j=0;j<actual.size();j++) {
                Node n=actual.get(j);if(n.sourcePostorder==0)continue;
                pairs.add(new int[]{n.sourcePostorder,j+1});String before=sourceNodes.get(n.sourcePostorder-1).label;
                if(before.equals(n.label))reads.add(new JSONObject().put("kind","read-keep").put("sourcePostorder",n.sourcePostorder)
                        .put("targetPostorder",j+1).put("before",before).put("after",n.label).put("cost",0));
            }
            pairs.sort(java.util.Comparator.comparingInt(p->p[0]));reads.sort(java.util.Comparator.comparingInt(p->p.getInt("sourcePostorder")));
            JSONArray expected=new JSONArray();for(var pair:pairs)expected.put(new JSONArray(pair));
            require(expected.similar(receipt.getJSONArray("selectedCorrespondence"))
                    && new JSONArray(reads).similar(receipt.getJSONArray("zeroCostReads")),"Temporal retained occurrence correspondence");
        }
        require(cost == DeclarationReplay.integer(receipt, "cost")
                && cost == DeclarationReplay.integer(receipt, "replayedCost"), "Temporal replay cost");
        return cost;
    }

    private static JSONObject edit(String kind, JSONArray path) {
        return new JSONObject().put("kind", kind).put("path", path).put("cost", 1);
    }

    private static void apply(Node forest, JSONObject edit) {
        require(DeclarationReplay.integer(edit, "cost") == 1, "Temporal unit cost");
        Node node = resolve(forest, edit.getJSONArray("path"));
        switch (edit.getString("kind")) {
            case "delete" -> {
                require(node != forest && node.label.equals(edit.getString("label"))
                        && node.children.size() == DeclarationReplay.integer(edit, "childCount"),
                        "Temporal delete occurrence");
                int index = node.parent.children.indexOf(node);
                node.parent.children.remove(index);
                node.parent.children.addAll(index, node.children);
                for (Node child : node.children) child.parent = node.parent;
                node.parent = null;
            }
            case "update" -> {
                String to = edit.getString("to");
                require(node != forest && node.label.equals(edit.getString("from"))
                        && !node.label.equals(to), "Temporal update occurrence");
                TraceResources.requireBytes(Math.multiplyExact(2L,to.length()));
                node.label = to;
            }
            case "insert" -> {
                int index = DeclarationReplay.integer(edit, "index");
                int count = DeclarationReplay.integer(edit, "childCount");
                require(index >= 0 && count >= 0 && index <= node.children.size()
                        && count <= node.children.size() - index, "Temporal insert interval");
                String value = edit.getString("label");
                TraceResources.requireBytes(Math.multiplyExact(2L,value.length()));
                Node inserted = new Node(value, node);
                inserted.children.addAll(node.children.subList(index, index + count));
                node.children.subList(index, index + count).clear();
                for (Node child : inserted.children) child.parent = inserted;
                node.children.add(index, inserted);
            }
            default -> throw new IllegalArgumentException("Unknown temporal edit");
        }
    }

    private static Node resolve(Node forest, JSONArray path) {
        TraceResources.requireBytes(Math.multiplyExact(8L,path.length()));
        Node node = forest;
        for (int i = 0; i < path.length(); i++) {
            int index = DeclarationReplay.integer(new JSONObject().put("index", path.get(i)), "index");
            require(index >= 0 && index < node.children.size(), "Temporal path occurrence");
            node = node.children.get(index);
        }
        return node;
    }

    private static JSONArray path(Node node) {
        List<Integer> indices = new ArrayList<>();
        while (node.parent != null) {
            indices.add(node.parent.children.indexOf(node));
            node = node.parent;
        }
        java.util.Collections.reverse(indices);
        return new JSONArray(indices);
    }

    private static final class Node {
        String label;
        int sourcePostorder;
        Node parent;
        final List<Node> children = new ArrayList<>();
        Node(String label, Node parent) { this.label = label; this.parent = parent; }
    }

    private static Node forest(Tree tree, List<Node> postorder) {
        Node forest = new Node("", null);
        forest.children.add(mutable(tree, forest, postorder));
        return forest;
    }

    private static Node mutable(Tree tree, Node parent, List<Node> postorder) {
        Node node = new Node(tree.label, parent);
        for (Tree child : tree.children) node.children.add(mutable(child, node, postorder));
        postorder.add(node);
        node.sourcePostorder=postorder.size();
        return node;
    }

    private static void mutablePostorder(Node node,List<Node> out) {
        for(Node child:node.children)mutablePostorder(child,out);out.add(node);
    }

    private static <N> Tree snapshot(N node, Function<N, String> label,
            Function<N, List<N>> children, TraceResources budget) {
        String value = Objects.requireNonNull(label.apply(node));
        budget.spend(Math.addExact(64L,Math.multiplyExact(2L,value.length())));
        List<Tree> result = new ArrayList<>();
        for (N child : children.apply(node)) result.add(snapshot(child, label, children, budget));
        return new Tree(value, List.copyOf(result));
    }

    private static int size(Tree root) {
        int count=0;var pending=new java.util.ArrayDeque<Tree>();pending.add(root);
        while(!pending.isEmpty()){var node=pending.removeFirst();count=Math.addExact(count,1);pending.addAll(node.children);}
        return count;
    }

    private static void postorder(Tree tree, List<Tree> result) {
        for (Tree child : tree.children) postorder(child, result);
        result.add(tree);
    }

    private static void preorder(Tree tree, List<Tree> result) {
        result.add(tree);
        for (Tree child : tree.children) preorder(child, result);
    }

    private static JSONArray json(Tree tree) {
        JSONArray children = new JSONArray();
        for (Tree child : tree.children) children.put(json(child));
        return new JSONArray().put(tree.label).put(children);
    }

    private static JSONArray json(Node tree) {
        JSONArray children = new JSONArray();
        for (Node child : tree.children) children.put(json(child));
        return new JSONArray().put(tree.label).put(children);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
