package is.fivefivefive.CanDis.adapter;

import java.util.ArrayList;
import java.util.List;

import is.fivefivefive.CanDis.core.egraph.AlloyTerm;
import parser.ast.nodes.BinaryExpr;
import parser.ast.nodes.BinaryFormula;
import parser.ast.nodes.Call;
import parser.ast.nodes.ConstExpr;
import parser.ast.nodes.FieldExpr;
import parser.ast.nodes.ITEExpr;
import parser.ast.nodes.ITEFormula;
import parser.ast.nodes.ListExpr;
import parser.ast.nodes.ListFormula;
import parser.ast.nodes.Node;
import parser.ast.nodes.ParamDecl;
import parser.ast.nodes.Predicate;
import parser.ast.nodes.QtExpr;
import parser.ast.nodes.QtFormula;
import parser.ast.nodes.RelDecl;
import parser.ast.nodes.SigExpr;
import parser.ast.nodes.UnaryExpr;
import parser.ast.nodes.UnaryFormula;
import parser.ast.nodes.VarExpr;

/** Converts parser AST nodes into the parser-independent e-graph term model. */
public final class AlloyAstTermAdapter {
    private AlloyAstTermAdapter() {
    }

    public static AlloyTerm fromPredicate(Predicate predicate) {
        List<AlloyTerm> children = new ArrayList<>();
        if (predicate != null) {
            List<ParamDecl> parameters = predicate.getParamList();
            if (parameters != null) {
                for (ParamDecl parameter : parameters) {
                    children.add(fromAst(parameter));
                }
            }
            if (predicate.getBody() != null) {
                children.add(fromAst(predicate.getBody()));
            }
        }
        return AlloyTerm.node("PREDICATE", children);
    }

    public static AlloyTerm fromAst(Node node) {
        if (node == null) {
            return AlloyTerm.atom("NULL", "");
        }
        if (node instanceof RelDecl) {
            RelDecl declaration = (RelDecl) node;
            List<AlloyTerm> children = new ArrayList<>();
            if (declaration.getVariables() != null) {
                for (Node variable : declaration.getVariables()) {
                    children.add(fromAst(variable));
                }
            }
            if (declaration.getExpr() != null) {
                children.add(fromAst(declaration.getExpr()));
            }
            return AlloyTerm.node("DECL/" + node.getClass().getSimpleName()
                    + "/disj=" + declaration.isDisjoint()
                    + "/var=" + declaration.isVariable(), children);
        }
        List<AlloyTerm> children = new ArrayList<>();
        List<Node> astChildren = node.getChildren();
        if (astChildren != null) {
            for (Node child : astChildren) {
                if (child != null) {
                    children.add(fromAst(child));
                }
            }
        }
        if (node instanceof VarExpr) {
            return AlloyTerm.atom("VAR", ((VarExpr) node).getName());
        }
        if (node instanceof SigExpr) {
            String name = ((SigExpr) node).getName();
            if ("none".equals(name) || "univ".equals(name)) {
                return AlloyTerm.atom("CONST", name);
            }
            return AlloyTerm.atom("SIG", name);
        }
        if (node instanceof FieldExpr) {
            return AlloyTerm.atom("FIELD", ((FieldExpr) node).getName());
        }
        if (node instanceof ConstExpr) {
            return AlloyTerm.atom("CONST", ((ConstExpr) node).getValue());
        }
        if (node instanceof BinaryFormula) {
            return AlloyTerm.node("BF/" + enumName(((BinaryFormula) node).getOp()), children);
        }
        if (node instanceof BinaryExpr) {
            return AlloyTerm.node("BE/" + enumName(((BinaryExpr) node).getOp()), children);
        }
        if (node instanceof UnaryFormula) {
            return AlloyTerm.node("UF/" + enumName(((UnaryFormula) node).getOp()), children);
        }
        if (node instanceof UnaryExpr) {
            return AlloyTerm.node("UE/" + enumName(((UnaryExpr) node).getOp()), children);
        }
        if (node instanceof QtFormula) {
            return AlloyTerm.node("QF/" + enumName(((QtFormula) node).getOp()), children);
        }
        if (node instanceof QtExpr) {
            return AlloyTerm.node("QE/" + enumName(((QtExpr) node).getOp()), children);
        }
        if (node instanceof ListFormula) {
            return AlloyTerm.node("LF/" + enumName(((ListFormula) node).getOp()), children);
        }
        if (node instanceof ListExpr) {
            return AlloyTerm.node("LE/" + enumName(((ListExpr) node).getOp()), children);
        }
        if (node instanceof Call) {
            return AlloyTerm.of("CALL/" + node.getClass().getSimpleName(),
                    ((Call) node).getName(), children);
        }
        if (node instanceof ITEFormula) {
            return AlloyTerm.node("ITE/FORMULA", children);
        }
        if (node instanceof ITEExpr) {
            return AlloyTerm.node("ITE/EXPR", children);
        }
        return AlloyTerm.node(node.getClass().getSimpleName(), children);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? "NULL" : value.name();
    }
}
