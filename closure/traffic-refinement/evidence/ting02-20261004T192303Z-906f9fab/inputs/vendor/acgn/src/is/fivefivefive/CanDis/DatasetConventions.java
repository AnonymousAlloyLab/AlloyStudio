package is.fivefivefive.CanDis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import parser.ast.nodes.BinaryExpr;
import parser.ast.nodes.BinaryFormula;
import parser.ast.nodes.Call;
import parser.ast.nodes.ConstExpr;
import parser.ast.nodes.FieldExpr;
import parser.ast.nodes.ListExpr;
import parser.ast.nodes.ListFormula;
import parser.ast.nodes.Node;
import parser.ast.nodes.Paragraph;
import parser.ast.nodes.QtExpr;
import parser.ast.nodes.QtFormula;
import parser.ast.nodes.RelDecl;
import parser.ast.nodes.SigExpr;
import parser.ast.nodes.UnaryExpr;
import parser.ast.nodes.UnaryFormula;
import parser.ast.nodes.VarExpr;

final class DatasetConventions {
    private DatasetConventions() {
    }

    static String normalizeStatusFolder(String folder) {
        if (folder == null || folder.isEmpty()) {
            return "";
        }
        String normalized = folder.trim().toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        switch (normalized) {
            case "CORRECT":
                return "CORRECT";
            case "BOTH":
                return "BOTH";
            case "OVER":
            case "OVERCONSTRAINED":
                return "OVERCONSTRAINED";
            case "UNDER":
            case "UNDERCONSTRAINED":
                return "UNDERCONSTRAINED";
            default:
                return folder.trim().toUpperCase(Locale.ROOT);
        }
    }

    static String[] findPredicatePairNames(String preferred, Map<String, ?> predicates) {
        if (preferred != null) {
            String student = matchingName(preferred, predicates);
            String oracle = student == null ? null : matchingName(student + "C", predicates);
            if (oracle != null && !oracle.equals(student)) {
                return new String[] { student, oracle };
            }
        }

        List<String> names = new ArrayList<>(predicates.keySet());
        names.sort(Comparator.comparing((String name) -> name.toLowerCase(Locale.ROOT))
                .thenComparing(Comparator.naturalOrder()));
        for (String oracle : names) {
            if (oracle.length() <= 1 || Character.toLowerCase(oracle.charAt(oracle.length() - 1)) != 'c') {
                continue;
            }
            String student = matchingName(oracle.substring(0, oracle.length() - 1), predicates);
            if (student != null && !student.equals(oracle)) {
                return new String[] { student, oracle };
            }
        }
        return null;
    }

    static boolean sameRawAst(Node left, Node right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || !rawAstLabel(left).equals(rawAstLabel(right))) {
            return false;
        }
        List<Node> leftChildren = rawAstChildren(left);
        List<Node> rightChildren = rawAstChildren(right);
        if (leftChildren.size() != rightChildren.size()) {
            return false;
        }
        for (int i = 0; i < leftChildren.size(); i++) {
            if (!sameRawAst(leftChildren.get(i), rightChildren.get(i))) {
                return false;
            }
        }
        return true;
    }

    static String rawAstLabel(Node node) {
        String kind = node.getClass().getSimpleName();
        if (node instanceof RelDecl) {
            RelDecl declaration = (RelDecl) node;
            return kind + ":disjoint=" + declaration.isDisjoint() + ":variable=" + declaration.isVariable();
        }
        if (node instanceof VarExpr) {
            return kind + ":name=" + ((VarExpr) node).getName();
        }
        if (node instanceof SigExpr) {
            SigExpr signature = (SigExpr) node;
            return kind + ":name=" + resolvedSignatureName(signature)
                    + ":variable=" + signature.isVariable();
        }
        if (node instanceof FieldExpr) {
            FieldExpr field = (FieldExpr) node;
            return kind + ":name=" + resolvedFieldName(field) + ":variable=" + field.isVariable();
        }
        if (node instanceof ConstExpr) {
            ConstExpr constant = (ConstExpr) node;
            return kind + ":value=" + constant.getValue() + ":boolean=" + constant.isBoolean();
        }
        if (node instanceof BinaryFormula) {
            return kind + ":op=" + enumName(((BinaryFormula) node).getOp());
        }
        if (node instanceof BinaryExpr) {
            return kind + ":op=" + enumName(((BinaryExpr) node).getOp());
        }
        if (node instanceof UnaryFormula) {
            return kind + ":op=" + enumName(((UnaryFormula) node).getOp());
        }
        if (node instanceof UnaryExpr) {
            return kind + ":op=" + enumName(((UnaryExpr) node).getOp());
        }
        if (node instanceof QtFormula) {
            return kind + ":op=" + enumName(((QtFormula) node).getOp());
        }
        if (node instanceof QtExpr) {
            return kind + ":op=" + enumName(((QtExpr) node).getOp());
        }
        if (node instanceof ListFormula) {
            return kind + ":op=" + enumName(((ListFormula) node).getOp());
        }
        if (node instanceof ListExpr) {
            return kind + ":op=" + enumName(((ListExpr) node).getOp());
        }
        if (node instanceof Call) {
            return kind + ":name=" + resolvedCallName((Call) node);
        }
        if (node instanceof Paragraph) {
            return kind + ":name=" + ((Paragraph) node).getName();
        }
        return kind;
    }

    static List<Node> rawAstChildren(Node node) {
        if (!(node instanceof RelDecl)) {
            return node.getChildren();
        }
        RelDecl declaration = (RelDecl) node;
        List<Node> children = new ArrayList<>(declaration.getVariables().size() + 1);
        children.addAll(declaration.getVariables());
        if (declaration.getExpr() != null) {
            children.add(declaration.getExpr());
        }
        return children;
    }

    private static String resolvedSignatureName(SigExpr signature) {
        return signature.getName() + "@" + signature.getType();
    }

    private static String resolvedFieldName(FieldExpr field) {
        return field.getName() + "@" + field.getType();
    }

    private static String resolvedCallName(Call call) {
        return call.getName() + "@" + call.getType();
    }

    private static String enumName(Enum<?> value) {
        return value == null ? "null" : value.name();
    }

    private static String matchingName(String requested, Map<String, ?> predicates) {
        if (predicates.containsKey(requested)) {
            return requested;
        }
        String best = null;
        for (String name : predicates.keySet()) {
            if (name.equalsIgnoreCase(requested) && (best == null || name.compareTo(best) < 0)) {
                best = name;
            }
        }
        return best;
    }
}
