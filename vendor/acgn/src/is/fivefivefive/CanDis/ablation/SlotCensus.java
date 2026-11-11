package is.fivefivefive.CanDis.ablation;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import parser.ast.nodes.LetExpr;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;
import parser.ast.nodes.RelDecl;
import parser.ast.nodes.VarExpr;
import parser.util.AlloyUtil;

/** Source binder occurrences, not variable usages or e-class interface slots. */
public record SlotCensus(long total, Map<String, Long> byType) {
    public SlotCensus {
        byType = Collections.unmodifiableMap(new TreeMap<>(byType));
        if (byType.values().stream().anyMatch(n -> n < 0)
                || total != byType.values().stream().mapToLong(Long::longValue).sum()) {
            throw new IllegalArgumentException("Slot census does not sum to total");
        }
    }

    public static SlotCensus fromPredicate(Predicate predicate) {
        Map<String, Long> counts = new TreeMap<>();
        for (var parameter : predicate.getParamList()) visit(parameter, counts);
        visit(predicate.getBody(), counts);
        return new SlotCensus(counts.values().stream().mapToLong(Long::longValue).sum(), counts);
    }

    private static void visit(Node node, Map<String, Long> counts) {
        node = AlloyUtil.unwrapAst(node);
        if (node == null) return;
        if (node instanceof RelDecl declaration) {
            for (var variable : declaration.getVariables()) count(variable, counts);
            visit(declaration.getExpr(), counts);
            return;
        }
        if (node instanceof LetExpr let) {
            count(let.getVar(), counts);
            visit(let.getBound(), counts);
            visit(let.getBody(), counts);
            return;
        }
        if (node.getChildren() != null) {
            for (Node child : node.getChildren()) visit(child, counts);
        }
    }

    private static void count(Node node, Map<String, Long> counts) {
        node = AlloyUtil.unwrapAst(node);
        if (!(node instanceof VarExpr variable)) {
            throw new IllegalArgumentException("Binding is not a variable");
        }
        var type = ExactAlloyType.from(variable.getType());
        String key = type.kind() == ExactAlloyType.Kind.UNKNOWN ? "<unknown>" : type.toString();
        counts.merge(key, 1L, Long::sum);
    }
}
