package is.fivefivefive.ACGN.repair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.*;

/** Conservative source menus; reparsing, not a type-name guess, admits a candidate. */
public final class RepairActionEnumerator {
    public static final int MAX_MENU = 512;
    private static final List<ExprBinary.Op> BOOL = List.of(ExprBinary.Op.AND,
            ExprBinary.Op.OR, ExprBinary.Op.IMPLIES, ExprBinary.Op.IFF);
    private static final List<ExprBinary.Op> REL = List.of(ExprBinary.Op.PLUS,
            ExprBinary.Op.INTERSECT, ExprBinary.Op.MINUS);
    private static final List<ExprUnary.Op> CARD = List.of(ExprUnary.Op.NO,
            ExprUnary.Op.SOME, ExprUnary.Op.ONE, ExprUnary.Op.LONE);

    private RepairActionEnumerator() { }

    public record Menu(List<RepairAction> actions, int total, boolean truncated) {
        public Menu { actions = List.copyOf(actions); }
    }

    public static Menu enumerate(RepairEnvironment.State state) {
        Builder builder = new Builder(state);
        builder.visit(state.candidate.expression(), "root", 0);
        List<RepairAction> all = new ArrayList<>(builder.actions.values());
        all.sort(Comparator.comparing(RepairAction::path).thenComparing(a -> a.kind().name())
                .thenComparing(RepairAction::replacement));
        int total = all.size() + 1;
        if (all.size() >= MAX_MENU) all = new ArrayList<>(all.subList(0, MAX_MENU - 1));
        all.add(new RepairAction(state.task.id(), state.candidate.hash(), state.control().revision(),
                RepairAction.Kind.STOP, "root", 0, 0, "", ""));
        return new Menu(all, total, total > MAX_MENU);
    }

    private static final class Builder {
        private final RepairEnvironment.State state;
        private final String source;
        private final Map<String, RepairAction> actions = new LinkedHashMap<>();

        private Builder(RepairEnvironment.State state) {
            this.state = state;
            source = state.task.source(state.body());
        }

        private void visit(Expr expr, String path, int depth) {
            if (depth > 128) throw new RepairTask.UnsupportedInput("Occurrence depth exceeds 128");
            if (!(expr instanceof ExprUnary u && u.op == ExprUnary.Op.NOOP)) {
                String text = text(expr.span());
                if (expr.type().is_bool && complete(text)) {
                    add(expr.span(), path, RepairAction.Kind.WRAP_NOT, "(not (" + text + "))", "NOT");
                }
                if (expr instanceof ExprUnary unary) {
                    if (unary.op == ExprUnary.Op.NOT) {
                        String token = text(unary.pos);
                        if ("not".equals(token) || "!".equals(token)) {
                            add(unary.pos, path, RepairAction.Kind.UNWRAP_NOT, "", "REMOVE_NOT");
                        }
                    } else if (CARD.contains(unary.op)) {
                        for (ExprUnary.Op op : CARD) {
                            if (op != unary.op) replaceToken(unary.pos, path, unary.op.toString(),
                                    op.toString(), RepairAction.Kind.REPLACE_OPERATOR);
                        }
                    }
                } else if (expr instanceof ExprBinary binary) {
                    List<ExprBinary.Op> alternatives = BOOL.contains(binary.op) ? BOOL
                            : relationalMask(binary) ? REL : List.of();
                    for (ExprBinary.Op op : alternatives) {
                        if (op == binary.op) continue;
                        Expr proposed = op.make(binary.pos, binary.closingBracket, binary.left, binary.right);
                        String left = text(binary.left.span());
                        String right = text(binary.right.span());
                        if (proposed.errors.size() == 0 && proposed.type().equals(binary.type())
                                && complete(text) && complete(left) && complete(right)) {
                            add(binary.span(), path, RepairAction.Kind.REPLACE_OPERATOR,
                                    "((" + left + ") " + op + " (" + right + "))", op.name());
                        }
                    }
                } else if (expr instanceof ExprList list
                        && (list.op == ExprList.Op.AND || list.op == ExprList.Op.OR)
                        && list.args.size() >= 2 && complete(text)) {
                    String token = list.op == ExprList.Op.AND ? "or" : "and";
                    List<String> children = new ArrayList<>();
                    for (Expr child : list.args) {
                        String childText = text(child.span());
                        if (!complete(childText)) { children.clear(); break; }
                        children.add("(" + childText + ")");
                    }
                    if (!children.isEmpty()) add(expr.span(), path, RepairAction.Kind.REPLACE_OPERATOR,
                            "(" + String.join(" " + token + " ", children) + ")", token);
                } else if (expr instanceof ExprQt qt && qt.type().is_bool) {
                    for (String quantifier : List.of("all", "some", "no", "one", "lone")) {
                        if (!quantifier.equals(qt.op.toString())) replaceToken(qt.pos, path,
                                qt.op.toString(), quantifier, RepairAction.Kind.MODIFY_QUANTIFIER);
                    }
                }
            }
            List<Expr> children = RepairTask.children(expr);
            for (int i = 0; i < children.size(); i++) visit(children.get(i), path + "/" + i, depth + 1);
        }

        private static boolean relationalMask(ExprBinary binary) {
            return REL.contains(binary.op) && !binary.type().is_bool && !binary.type().is_int()
                    && !binary.left.type().is_int() && !binary.right.type().is_int()
                    && binary.left.type().equals(binary.right.type())
                    && binary.left.type().equals(binary.type());
        }

        private static boolean complete(String text) {
            if (text == null || text.contains("\"") || text.contains("//")
                    || text.contains("--") || text.contains("/*")) return false;
            java.util.ArrayDeque<Character> closing = new java.util.ArrayDeque<>();
            for (char c : text.toCharArray()) {
                if (c == '(') closing.push(')');
                else if (c == '[') closing.push(']');
                else if (c == '{') closing.push('}');
                else if (c == ')' || c == ']' || c == '}') {
                    if (closing.isEmpty() || closing.pop() != c) return false;
                }
            }
            return closing.isEmpty();
        }

        private void replaceToken(Pos pos, String path, String oldToken,
                                  String newToken, RepairAction.Kind kind) {
            // Alternate Alloy spellings are edited only when the parser's token span agrees.
            if (oldToken.equals(text(pos))) add(pos, path, kind, newToken, newToken);
        }

        private String text(Pos pos) {
            int[] range = range(pos);
            return range == null ? null : source.substring(range[0], range[1]);
        }

        private int[] range(Pos pos) {
            if (pos == null || !RepairTask.VIRTUAL_FILE.equals(pos.filename)) return null;
            int[] range = pos.toStartEnd(source);
            int start = state.task.bodyStart();
            if (range == null || range[0] < start || range[1] > start + state.body().length()
                    || range[1] <= range[0]) return null;
            return range;
        }

        private void add(Pos pos, String path, RepairAction.Kind kind, String replacement, String operation) {
            int[] range = range(pos);
            if (range == null) return;
            String expected = source.substring(range[0], range[1]);
            if (expected.equals(replacement)) return;
            int start = range[0] - state.task.bodyStart();
            int end = range[1] - state.task.bodyStart();
            String key = start + ":" + end + ":" + replacement;
            actions.putIfAbsent(key, new RepairAction(state.task.id(), state.candidate.hash(),
                    state.control().revision(), kind, path, start, end, expected, replacement, operation));
        }
    }
}
