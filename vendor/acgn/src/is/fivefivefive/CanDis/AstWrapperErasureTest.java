package is.fivefivefive.CanDis;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import is.fivefivefive.CanDis.core.egraph.AlloyTerm;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import parser.ast.nodes.Body;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.RelDecl;
import parser.ast.nodes.UnaryExpr;
import parser.ast.nodes.UnaryFormula;
import parser.ast.visitor.PrettyStringVisitor;
import parser.util.AlloyUtil;

/** Source-derived correspondence checks for the transparent AST view. */
public final class AstWrapperErasureTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        ModelUnit model = new ModelUnit(null, CompUtil.parseEverything_fromString(A4Reporter.NOP, """
                sig A { r: set A }
                sig B {}
                pred p[x:A] {
                  let t = A->A | all y:A | some z:y.r | not (some (~t & ^r))
                }
                pred temporal { always (some A implies after no r) }
                pred counts { #A > 0 and lone B }
                pred qa { all x:A | some x }
                pred qb { all x:B | some x }
                pred called { p[A] }
                """));
        Method batchDistance = method(CanonicalBatchTest.class, "rawAstTreeDistance", Node.class, Node.class);
        Method batchSize = method(CanonicalBatchTest.class, "rawAstSize", Node.class);
        Class<?> rawTree = Class.forName("is.fivefivefive.CanDis.Alloy4FunAugmenter$RawAstTree");
        Method from = method(rawTree, "from", Node.class);
        Method augmentDistance = method(Alloy4FunAugmenter.class, "rawAstTreeDistance", rawTree, rawTree);
        Method export = method(RepairCorpusSource.class, "ast", Node.class);
        Set<String> heads = new HashSet<>();
        int bodies = 0, noops = 0;
        for (var predicate : model.getPredDeclList()) {
            for (boolean typed : List.of(false, true)) {
                AlloyTerm term = AlloyAstTermAdapter.fromPredicate(predicate, typed);
                checkTerm(term, heads);
                check(AlloyAstTermAdapter.fromPredicate(predicate, true).eraseSlotTypes().equals(
                        AlloyAstTermAdapter.fromPredicate(predicate)), "typed erasure keeps the same tree");
            }
            List<Node> sourceNodes = new ArrayList<>();
            collect(predicate, sourceNodes);
            for (Node node : sourceNodes) {
                Node semantic = AlloyUtil.unwrapAst(node);
                check(AlloyUtil.unwrapAst(semantic) == semantic, "view is idempotent and retains source object");
                check(AlloyAstTermAdapter.fromAst(node).size() == semanticSize(node),
                        "one term occurrence per non-decorative AST occurrence");
                if (node instanceof UnaryExpr unary && unary.getOp() == UnaryExpr.UnaryOp.NOOP) {
                    check(node.accept(new PrettyStringVisitor(), null).equals(
                            unary.getSub().accept(new PrettyStringVisitor(), null)),
                            "legacy printer renders NOOP as exactly its child");
                }
                if (semantic == node) continue;
                if (node instanceof Body) bodies++;
                if (node instanceof UnaryExpr) noops++;
                check(DatasetConventions.sameRawAst(node, semantic), "wrapper-transparent AST equality");
                check((int) batchDistance.invoke(null, node, semantic) == 0, "batch wrapper edit cost is zero");
                check(batchSize.invoke(null, node).equals(batchSize.invoke(null, semantic)),
                        "wrapper does not contribute to insertion/deletion cost");
                check((int) augmentDistance.invoke(null, from.invoke(null, node), from.invoke(null, semantic)) == 0,
                        "augmenter wrapper edit cost is zero");
                check(export.invoke(null, node).toString().equals(export.invoke(null, semantic).toString()),
                        "source export excludes wrappers");
                check(AlloyAstTermAdapter.fromAst(node, true).equals(AlloyAstTermAdapter.fromAst(semantic, true)),
                        "typed AST adaptation excludes wrappers");
            }
        }
        check(bodies > 0 && noops > 0, "real parser supplied both wrapper kinds");
        check(heads.containsAll(Set.of("UF/NOT", "UF/SOME", "UF/ALWAYS", "UF/AFTER",
                "UE/TRANSPOSE", "UE/CLOSURE", "UE/CARDINALITY", "LetExpr", "QF/ALL")),
                "real operators and binders retained");
        var qa = model.getPredDeclList().stream().filter(p -> p.getName().equals("qa")).findFirst().orElseThrow();
        var qb = model.getPredDeclList().stream().filter(p -> p.getName().equals("qb")).findFirst().orElseThrow();
        check(!DatasetConventions.sameRawAst(qa.getBody(), qb.getBody()), "binding domains remain distinct");
        for (var op : UnaryFormula.UnaryOp.values()) {
            check(!op.name().equals("NOOP"), "pinned parser formula operators are semantic");
        }
        boolean rejected = false;
        try { AlloyUtil.unwrapAst(new Body(null)); }
        catch (NullPointerException expected) { rejected = true; }
        check(rejected, "missing wrapper child fails closed");
        System.out.println("AstWrapperErasureTest: " + checks + " checks passed");
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static int semanticSize(Node node) {
        if (node instanceof Body body) return semanticSize(body.getBodyExpr());
        if (node instanceof UnaryExpr unary && unary.getOp() == UnaryExpr.UnaryOp.NOOP)
            return semanticSize(unary.getSub());
        int size = 1;
        if (node instanceof RelDecl declaration) {
            for (Node variable : declaration.getVariables()) size += semanticSize(variable);
            return size + semanticSize(declaration.getExpr());
        }
        for (Node child : node.getChildren()) if (child != null) size += semanticSize(child);
        return size;
    }

    private static void collect(Node node, List<Node> result) {
        if (node == null) return;
        result.add(node);
        if (node instanceof RelDecl declaration) declaration.getVariables().forEach(n -> collect(n, result));
        if (node.getChildren() != null) node.getChildren().forEach(n -> collect(n, result));
    }

    private static void checkTerm(AlloyTerm term, Set<String> heads) {
        check(!term.head().equals("Body") && !term.head().equals("UE/NOOP"), "no decorative term nodes");
        heads.add(term.head());
        term.children().forEach(child -> checkTerm(child, heads));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
