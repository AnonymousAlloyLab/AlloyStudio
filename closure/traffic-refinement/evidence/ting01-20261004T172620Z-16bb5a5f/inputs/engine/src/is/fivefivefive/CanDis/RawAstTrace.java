package is.fivefivefive.CanDis;

import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.*;
import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;
import parser.ast.nodes.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Portal adapter for the dataset's raw AST labels and child order.
 *
 * Distance authority is the pinned unit-cost Zhang--Shasha implementation.
 * The independent forest backtrace retains an optimal node mapping, then checks
 * it by actually deleting nodes with child promotion, relabeling nodes, and
 * inserting nodes with adoption of consecutive children. Private target labels
 * are confined to this verifier; the outward Edit type contains learner context
 * and an optional replacement operator from a fixed vocabulary only.
 */
public final class RawAstTrace {
    public static final int MAX_NODES = 1024;
    public static final long MAX_FOREST_CELLS = 8_000_000L;
    private static final Set<String> SAFE_OPERATORS = Set.of(
            "all", "some", "no", "one", "lone", "set", "exactly", "not", "and", "or",
            "implies", "iff", "=", "!=", "in", "!in", ">", ">=", "<", "<=", "!>", "!>=", "!<", "!<=",
            ".", "->", "&", "+", "++", "-", "~", "^", "*", "#", "<:", ":>", "'",
            "always", "eventually", "after", "before", "historically", "once", "until", "releases", "since", "triggered",
            "sum", "disj", "int", "Int", "/", "%", "<<", ">>", ">>>", "if-then-else",
            "some->some", "some->one", "some->lone", "some->", "one->some", "one->one", "one->lone", "one->",
            "lone->some", "lone->one", "lone->lone", "lone->", "->some", "->one", "->lone");
    private static final OrderedTreeEditDistance.Adapter<Tree> ADAPTER = new OrderedTreeEditDistance.Adapter<>() {
        public String label(Tree tree) { return tree.label; }
        public List<Tree> children(Tree tree) { return tree.children; }
    };

    private RawAstTrace() { }

    public static Prepared prepare(CompModule module, String predicate) {
        ModelUnit model = MASGVisitor.modelWithSourceMap(module);
        Predicate selected = null;
        for (Predicate candidate : model.getPredDeclList()) {
            if (candidate.getName().equals(predicate) || candidate.getName().equals("this/" + predicate)) {
                if (selected != null || !candidate.getParamList().isEmpty()) throw new IllegalArgumentException("Ambiguous predicate");
                selected = candidate;
            }
        }
        if (selected == null) throw new IllegalArgumentException("Predicate unavailable");
        return new Prepared(from(selected.getBody(), new int[] {0}, 0));
    }

    private static Tree from(Node node, int[] count, int depth) {
        if (node == null || ++count[0] > MAX_NODES || depth > 256) throw new IllegalArgumentException("AST limit");
        List<Tree> children = new ArrayList<>();
        for (Node child : DatasetConventions.rawAstChildren(node)) children.add(from(child, count, depth + 1));
        return new Tree(DatasetConventions.rawAstLabel(node), children, node);
    }

    public static int distance(Prepared left, Prepared right) {
        checkBudget(left.index, right.index);
        return OrderedTreeEditDistance.distance(left.root, right.root, ADAPTER);
    }

    public static Result trace(Prepared left, Prepared right, int expected) {
        checkBudget(left.index, right.index);
        Solver solver = new Solver(left.index, right.index);
        if (solver.distance() != expected) throw new IllegalStateException("Distance authority disagreement");
        solver.backtrace(left.size(), right.size());
        List<PrivateEdit> privateEdits = replay(left.index, right.index, solver.leftToRight, solver.rightToLeft);
        if (privateEdits.size() != expected) throw new IllegalStateException("Replay cost disagreement");
        List<Edit> edits = new ArrayList<>();
        for (PrivateEdit edit : privateEdits) {
            int source = edit.left;
            if (source == 0) source = insertionAnchor(edit.right, left.index, right.index, solver.rightToLeft);
            Tree learner = source == 0 ? null : left.index.node(source);
            String replacement = edit.kind.equals("replace") ? operator(right.index.node(edit.right).original) : null;
            // Publishing unchanged operator names for a leaf-name replacement
            // would suggest a different edit. Only a changed operator is useful.
            if (Objects.equals(replacement, learner == null ? null : operator(learner.original))) replacement = null;
            edits.add(new Edit(edit.kind, source, learner == null ? null : sourcePosition(learner.original),
                    learner == null ? "structure" : nodeKind(learner.original),
                    learner == null ? null : operator(learner.original), replacement));
        }
        return new Result(expected, List.copyOf(edits));
    }

    public static final class Prepared {
        private final Tree root;
        private final Index index;
        Prepared(Tree root) { this.root = root; this.index = new Index(root); }
        public int size() { return index.size(); }
    }

    public record Result(int distance, List<Edit> edits) { }
    public record Edit(String kind, int sourceIndex, Pos sourcePosition, String sourceNodeKind,
                       String sourceOperator, String replacementOperator) { }

    /** Package-private constructor supports independent finite tree fixtures. */
    static final class Tree {
        final String label;
        final List<Tree> children;
        final Node original;
        Tree(String label, List<Tree> children, Node original) {
            this.label = Objects.requireNonNull(label);
            this.children = List.copyOf(children);
            this.original = original;
        }
    }

    private static void checkBudget(Index left, Index right) {
        if (left.size() > MAX_NODES || right.size() > MAX_NODES
                || Math.multiplyExact(left.forestWork(), right.forestWork()) > MAX_FOREST_CELLS)
            throw new IllegalArgumentException("AST pair limit");
    }

    private static final class Index {
        final List<Tree> nodes = new ArrayList<>();
        final List<Integer> leftmostList = new ArrayList<>();
        final IdentityHashMap<Tree, Integer> ids = new IdentityHashMap<>();
        final int[] leftmost, parent;
        final List<Integer> keyroots = new ArrayList<>();
        Index(Tree root) {
            nodes.add(null); leftmostList.add(0);
            append(root, 0);
            leftmost = leftmostList.stream().mapToInt(Integer::intValue).toArray();
            parent = new int[nodes.size()];
            int[] last = new int[nodes.size()];
            for (int i = 1; i < nodes.size(); i++) {
                last[leftmost[i]] = i;
                for (Tree child : node(i).children) parent[ids.get(child)] = i;
            }
            for (int i : last) if (i > 0) keyroots.add(i);
            keyroots.sort(Comparator.naturalOrder());
        }
        int append(Tree tree, int depth) {
            if (depth > 256 || ids.containsKey(tree)) throw new IllegalArgumentException("AST must be a finite occurrence tree");
            ids.put(tree, 0);
            int first = 0;
            for (Tree child : tree.children) {
                int leaf = append(child, depth + 1);
                if (first == 0) first = leaf;
            }
            if (nodes.size() > MAX_NODES) throw new IllegalArgumentException("AST limit");
            int id = nodes.size(); nodes.add(tree); ids.put(tree, id);
            leftmostList.add(first == 0 ? id : first);
            return first == 0 ? id : first;
        }
        int size() { return nodes.size() - 1; }
        Tree node(int id) { return nodes.get(id); }
        long forestWork() {
            long total = 0;
            for (int root : keyroots) total += root - leftmost[root] + 2L;
            return total;
        }
        boolean contains(int root, int descendant) { return leftmost[root] <= descendant && descendant <= root; }
    }

    private static final class Solver {
        final Index left, right;
        final int[][] treeDistance;
        final int[] leftToRight, rightToLeft;
        Solver(Index left, Index right) {
            this.left = left; this.right = right;
            treeDistance = new int[left.size() + 1][right.size() + 1];
            leftToRight = new int[left.size() + 1]; rightToLeft = new int[right.size() + 1];
            for (int l : left.keyroots) for (int r : right.keyroots) forest(l, r, true);
        }
        int distance() { return treeDistance[left.size()][right.size()]; }
        int[][] forest(int l, int r, boolean store) {
            int lb = left.leftmost[l], rb = right.leftmost[r];
            int[][] fd = new int[l - lb + 2][r - rb + 2];
            for (int i = 1; i < fd.length; i++) fd[i][0] = i;
            for (int j = 1; j < fd[0].length; j++) fd[0][j] = j;
            for (int a = lb; a <= l; a++) for (int b = rb; b <= r; b++) {
                int i = a - lb + 1, j = b - rb + 1;
                boolean roots = left.leftmost[a] == lb && right.leftmost[b] == rb;
                int align = roots ? fd[i - 1][j - 1] + update(a, b)
                        : fd[left.leftmost[a] - lb][right.leftmost[b] - rb] + treeDistance[a][b];
                fd[i][j] = Math.min(align, Math.min(fd[i - 1][j] + 1, fd[i][j - 1] + 1));
                if (store && roots) treeDistance[a][b] = fd[i][j];
            }
            return fd;
        }
        int update(int a, int b) { return left.node(a).label.equals(right.node(b).label) ? 0 : 1; }
        void backtrace(int l, int r) {
            int lb = left.leftmost[l], rb = right.leftmost[r];
            int[][] fd = forest(l, r, false);
            int a = l, b = r;
            while (a >= lb || b >= rb) {
                int i = a - lb + 1, j = b - rb + 1;
                if (a >= lb && b >= rb) {
                    boolean roots = left.leftmost[a] == lb && right.leftmost[b] == rb;
                    int align = roots ? fd[i - 1][j - 1] + update(a, b)
                            : fd[left.leftmost[a] - lb][right.leftmost[b] - rb] + treeDistance[a][b];
                    if (fd[i][j] == align) {
                        if (roots) {
                            if (leftToRight[a] != 0 || rightToLeft[b] != 0) throw new IllegalStateException("Repeated mapping");
                            leftToRight[a] = b; rightToLeft[b] = a; a--; b--;
                        } else {
                            backtrace(a, b);
                            a = left.leftmost[a] - 1; b = right.leftmost[b] - 1;
                        }
                        continue;
                    }
                }
                if (a >= lb && fd[i][j] == fd[i - 1][j] + 1) a--;
                else if (b >= rb && fd[i][j] == fd[i][j - 1] + 1) b--;
                else throw new IllegalStateException("No optimal forest transition");
            }
        }
    }

    private record PrivateEdit(String kind, int left, int right) { }
    private static final class Mutable {
        String label;
        int target;
        Mutable parent;
        final List<Mutable> children = new ArrayList<>();
        Mutable(String label, int target) { this.label = label; this.target = target; }
    }

    /** A real edit replay, including intermediate forests under a virtual root. */
    private static List<PrivateEdit> replay(Index left, Index right, int[] ltr, int[] rtl) {
        List<PrivateEdit> edits = new ArrayList<>();
        Mutable[] originals = new Mutable[left.size() + 1];
        for (int i = 1; i <= left.size(); i++) {
            originals[i] = new Mutable(left.node(i).label, ltr[i]);
            for (Tree child : left.node(i).children) {
                Mutable occurrence = originals[left.ids.get(child)];
                occurrence.parent = originals[i]; originals[i].children.add(occurrence);
            }
        }
        Mutable virtual = new Mutable("", 0);
        virtual.children.add(originals[left.size()]); originals[left.size()].parent = virtual;
        for (int i = 1; i <= left.size(); i++) if (ltr[i] == 0) {
            Mutable deleted = originals[i], parent = deleted.parent;
            int slot = parent.children.indexOf(deleted);
            if (slot < 0) throw new IllegalStateException("Missing deletion node");
            parent.children.remove(slot); parent.children.addAll(slot, deleted.children);
            for (Mutable child : deleted.children) child.parent = parent;
            deleted.children.clear(); deleted.parent = null;
            edits.add(new PrivateEdit("delete", i, 0));
        }
        for (int i = 1; i <= left.size(); i++) if (ltr[i] != 0) {
            if (rtl[ltr[i]] != i) throw new IllegalStateException("Noninjective mapping");
            if (!originals[i].label.equals(right.node(ltr[i]).label)) {
                originals[i].label = right.node(ltr[i]).label;
                edits.add(new PrivateEdit("replace", i, ltr[i]));
            }
        }
        insertTarget(right.size(), virtual, 0, right, rtl, originals, edits);
        if (virtual.children.size() != 1 || !sameTree(virtual.children.get(0), right.node(right.size())))
            throw new IllegalStateException("Private AST replay mismatch");
        return edits;
    }

    private static void insertTarget(int target, Mutable parent, int slot, Index right, int[] rtl,
                                     Mutable[] originals, List<PrivateEdit> edits) {
        Mutable current;
        if (rtl[target] != 0) {
            current = originals[rtl[target]];
            if (slot >= parent.children.size() || parent.children.get(slot) != current || current.parent != parent)
                throw new IllegalStateException("Mapping violates ancestor or sibling order");
        } else {
            current = new Mutable(right.node(target).label, target); current.parent = parent;
            while (slot < parent.children.size() && right.contains(target, parent.children.get(slot).target)) {
                Mutable adopted = parent.children.remove(slot);
                adopted.parent = current; current.children.add(adopted);
            }
            parent.children.add(slot, current);
            edits.add(new PrivateEdit("insert", 0, target));
        }
        int childSlot = 0;
        for (Tree child : right.node(target).children)
            insertTarget(right.ids.get(child), current, childSlot++, right, rtl, originals, edits);
        if (current.children.size() != childSlot) throw new IllegalStateException("Unexpected replay children");
    }

    private static boolean sameTree(Mutable actual, Tree expected) {
        if (!actual.label.equals(expected.label) || actual.children.size() != expected.children.size()) return false;
        for (int i = 0; i < actual.children.size(); i++) if (!sameTree(actual.children.get(i), expected.children.get(i))) return false;
        return true;
    }

    private static int insertionAnchor(int target, Index left, Index right, int[] rtl) {
        for (int parent = right.parent[target]; parent != 0; parent = right.parent[parent])
            if (rtl[parent] != 0) return visibleAnchor(rtl[parent], left);
        for (int descendant = right.leftmost[target]; descendant < target; descendant++)
            if (rtl[descendant] != 0) return visibleAnchor(rtl[descendant], left);
        return visibleAnchor(left.size(), left);
    }

    private static int visibleAnchor(int source, Index left) {
        // Body wrappers can span the fixed predicate braces. Prefer their real
        // child occurrence so the context remains inside the editable body.
        Tree tree = left.node(source);
        while (tree.children.size() == 1 && (tree.original instanceof Body || sourcePosition(tree.original) == null)) {
            tree = tree.children.get(0); source = left.ids.get(tree);
        }
        return source;
    }

    private static String operator(Node node) {
        String token = null;
        if (node instanceof UnaryFormula n) token = n.getOp().toString();
        else if (node instanceof UnaryExpr n) token = n.getOp().toString();
        else if (node instanceof BinaryFormula n) token = n.getOp().toString();
        else if (node instanceof BinaryExpr n) token = n.getOp().toString();
        else if (node instanceof QtFormula n) token = n.getOp().toString();
        else if (node instanceof QtExpr n) token = n.getOp().toString();
        else if (node instanceof ListFormula n) token = n.getOp().toString();
        else if (node instanceof ListExpr n) token = n.getOp().toString();
        else if (node instanceof ITEExprOrFormula) token = "if-then-else";
        if (token == null) return null;
        token = token.trim().replaceAll("\\s*->\\s*", "->");
        token = switch (token) {
            case "!" -> "not";
            case "&&" -> "and";
            case "||" -> "or";
            case "=>" -> "implies";
            case "<=>" -> "iff";
            case "not in" -> "!in";
            case "not >" -> "!>";
            case "not >=" -> "!>=";
            case "not <" -> "!<";
            case "not <=" -> "!<=";
            case "mul" -> "*";
            case "div" -> "/";
            case "rem" -> "%";
            case "shl" -> "<<";
            case "shr" -> ">>";
            case "sha" -> ">>>";
            default -> token;
        };
        return SAFE_OPERATORS.contains(token) ? token : null;
    }

    private static String nodeKind(Node node) {
        if (operator(node) != null) return "operator";
        if (node instanceof VarExpr) return "variable";
        if (node instanceof SigExpr || node instanceof FieldExpr) return "reference";
        if (node instanceof ConstExpr) return "constant";
        if (node instanceof Call) return "call";
        if (node instanceof RelDecl) return "binding";
        return "structure";
    }

    private static Pos sourcePosition(Node node) {
        if (node == null || node.getNodeMap() == null) return null;
        Object mapped = node.getNodeMap().findSrc(node);
        if (mapped instanceof Sig || mapped instanceof Sig.Field || mapped instanceof ExprVar) {
            Expr use = sourceUseSite(node, (Expr) mapped);
            if (use != null) mapped = use;
            else if (!(node.getParent() instanceof RelDecl declaration && declaration.getVariables().contains(node))) return null;
        }
        if (mapped instanceof Expr expression) return expression.span();
        if (mapped instanceof Decl declaration) return declaration.span();
        return null;
    }

    /** Recover declaration-owned leaves through their exact parser operand slot. */
    private static Expr sourceUseSite(Node source, Expr declaration) {
        Node branch = source;
        for (Node parent = source.getParent(); parent != null; parent = parent.getParent()) {
            Object mapped = parent.getNodeMap() == null ? null : parent.getNodeMap().findSrc(parent);
            Expr candidate = null;
            if (parent instanceof Body && mapped instanceof Expr expression) candidate = expression;
            else if (parent instanceof UnaryExprOrFormula n && mapped instanceof ExprUnary original && n.getSub() == branch)
                candidate = original.sub;
            else if (parent instanceof BinaryExprOrFormula n && mapped instanceof ExprBinary original)
                candidate = n.getLeft() == branch ? original.left : n.getRight() == branch ? original.right : null;
            else if (parent instanceof ListExprOrFormula n && mapped instanceof ExprList original) {
                int index = n.getArguments().indexOf(branch);
                if (index >= 0 && index < original.args.size()) candidate = original.args.get(index);
            } else if (parent instanceof Call n && mapped instanceof ExprCall original) {
                int index = n.getArguments().indexOf(branch);
                if (index >= 0 && index < original.args.size()) candidate = original.args.get(index);
            } else if (parent instanceof RelDecl n && mapped instanceof Decl original) {
                int index = n.getVariables().indexOf(branch);
                if (n.getExpr() == branch) candidate = original.expr;
                else if (index >= 0 && index < original.names.size()) candidate = original.names.get(index);
            } else if (parent instanceof ITEExprOrFormula n && mapped instanceof ExprITE original)
                candidate = n.getCondition() == branch ? original.cond
                        : n.getThenClause() == branch ? original.left : n.getElseClause() == branch ? original.right : null;
            else if (parent instanceof LetExpr n && mapped instanceof ExprLet original)
                candidate = n.getVar() == branch ? original.var : n.getBound() == branch ? original.expr : null;
            boolean declarationSlot = parent instanceof RelDecl relation && relation.getVariables().contains(branch)
                    || parent instanceof LetExpr let && let.getVar() == branch;
            if (candidate != null && candidate.deNOP() == declaration
                    && (candidate != declaration || declarationSlot)) return candidate;
            if (mapped instanceof Expr || mapped instanceof Decl) return null;
            branch = parent;
        }
        return null;
    }
}
