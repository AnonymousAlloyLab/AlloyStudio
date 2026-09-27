package live;

import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.*;
import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.LiveTrace;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.QuantiVar;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Learner-only source positions selected by the recorded canonical node path.
 * Parser origin metadata survives supported normalization steps independently
 * of semantic identity. Lexical candidates remain a conservative fallback when
 * a rewrite cannot retain a source origin; they never override a recorded one.
 */
final class SourceLocator {
    private static final int MAX_CANDIDATES = 16;
    private final String source;
    private final List<Integer> lineStarts = new ArrayList<>();
    private final Map<List<String>, List<Range>> expressions = new LinkedHashMap<>();
    private final Map<List<String>, List<Range>> bindings = new LinkedHashMap<>();
    private final Map<Range, Range> bindingHeaders = new LinkedHashMap<>();
    private Range body;
    private String filename;

    private record Range(int start, int end) { }

    private SourceLocator(String source, CompModule module, String predicate) {
        this.source = source;
        lineStarts.add(0);
        for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n') lineStarts.add(i + 1);
        for (Func function : module.getAllFunc()) {
            if (!function.isPred || !(function.label.equals(predicate) || function.label.equals("this/" + predicate))) continue;
            Expr expression = function.getBody();
            filename = expression.span().filename;
            body = range(expression.span());
            if (body != null) visit(expression);
            return;
        }
    }

    static void attach(String source, CompModule module, String predicate, Canonical.Prepared learner, JSONArray operations) {
        SourceLocator locator = null;
        try { locator = new SourceLocator(source, module, predicate); }
        catch (RuntimeException | StackOverflowError ignored) { /* Location failure cannot alter metric feedback. */ }
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i);
            JSONObject location;
            try {
                location = locator == null ? unavailable("A location in your code is unavailable for this hint.") : locator.locate(learner, operation);
            } catch (RuntimeException ignored) {
                location = unavailable("A location in your code is unavailable for this hint.");
            }
            operation.put("sourceLocation", location);
        }
    }

    private JSONObject locate(Canonical.Prepared learner, JSONObject operation) {
        JSONObject structural = structuralLocation(learner, operation);
        if (structural != null) return structural;
        String term = operation.optString("sourceTerm", "");
        if (term.isBlank() || term.endsWith("..."))
            return unavailable("This hint has no complete expression to highlight in your code.");
        Map<List<String>, List<Range>> index = operation.optString("component").equals("quantifier") ? bindings : expressions;
        List<Range> candidates = index.get(tokens(term));
        if (candidates == null || candidates.isEmpty())
            return unavailable("The simplified expression could not be matched reliably to your code.");

        // Parentheses and the predicate's brace wrapper can have the same token
        // sequence as a child. Keep the smallest parsed expression for each
        // occurrence, retaining every separate repeated occurrence.
        List<Range> minimal = new ArrayList<>();
        for (Range candidate : candidates) {
            boolean enclosesSmaller = false;
            for (Range other : candidates) {
                if (!candidate.equals(other) && candidate.start <= other.start && candidate.end >= other.end) {
                    enclosesSmaller = true;
                    break;
                }
            }
            if (!enclosesSmaller && !minimal.contains(candidate)) minimal.add(candidate);
        }
        if (minimal.size() > MAX_CANDIDATES)
            return unavailable("Too many parts of your code look alike to choose a useful highlight.");
        minimal.sort(Comparator.comparingInt(Range::start).thenComparingInt(Range::end));
        JSONArray ranges = new JSONArray();
        for (Range range : minimal) ranges.put(new JSONObject().put("start", range.start).put("end", range.end));
        boolean ambiguous = minimal.size() > 1;
        String reason = ambiguous
                ? "Several parts of your code match this hint. Inspect each highlighted possibility."
                : operation.optString("sourceRole").equals("insertion-anchor")
                ? "Inspect this area for a missing part; the highlight does not pinpoint where to add it."
                : "This highlight shows a related part of your code to inspect. The change you need may look different from the hint.";
        return metadata(ambiguous ? "ambiguous" : "located", "related", reason).put("ranges", ranges);
    }

    private JSONObject structuralLocation(Canonical.Prepared learner, JSONObject operation) {
        if (learner == null || operation.optBoolean("aggregate")) return null;
        String component = operation.optString("component"), path = operation.optString("path");
        boolean anchor = operation.optString("sourceRole").equals("insertion-anchor");
        if (component.equals("quantifier")) {
            // An inserted binding has no learner occurrence. Its target index
            // must never be interpreted as a learner declaration position.
            if (operation.optString("kind").equals("insert")) return null;
            QuantiVar binding = LiveTrace.learnerBinding(learner, path);
            if (binding == null) return null;
            Range original = originRange(binding.getSourceOrigin());
            if (original == null) return null;
            Range header = bindingHeaders.get(original);
            if (header == null) {
                for (Range candidate : bindingHeaders.values()) {
                    if (candidate.start <= original.start && original.start < candidate.end) {
                        if (header != null && !header.equals(candidate)) return null;
                        header = candidate;
                    }
                }
            }
            return insideBody(header) ? selected(header, false, true) : null;
        }
        if (!component.equals("matrix")) return null;
        EGraphNode node = LiveTrace.learnerNode(learner, path);
        if (node == null) return null;
        Range original = originRange(node.getSourceOrigin());
        if (insideBody(original)) return selected(original, anchor, true);
        // Some normalization steps synthesize nodes. A recorded enclosing
        // occurrence is useful context; it is explicitly not a node match.
        while (path.lastIndexOf(".child[") >= 0) {
            path = path.substring(0, path.lastIndexOf(".child["));
            node = LiveTrace.learnerNode(learner, path);
            if (node == null) break;
            original = originRange(node.getSourceOrigin());
            if (insideBody(original)) return selected(original, anchor, false);
        }
        return null;
    }

    private Range originRange(EGraphNode.SourceOrigin origin) {
        return origin == null ? null : range(new Pos(origin.filename(), origin.x(), origin.y(), origin.x2(), origin.y2()));
    }

    private JSONObject selected(Range span, boolean anchor, boolean node) {
        String reason = anchor
                ? "Inspect this recorded source expression for a missing part; the highlight does not pinpoint where to add it."
                : node ? "This is the source expression recorded for the node selected by this edit."
                : "This enclosing source expression contains the part selected by this edit.";
        return metadata("located", node ? "node" : "related", reason)
                .put("ranges", new JSONArray().put(new JSONObject().put("start", span.start).put("end", span.end)));
    }

    private void visit(Expr expression) {
        // Sig/Field/ExprVar objects are declaration-owned and can be shared by
        // occurrences. Their NOOP wrappers carry the actual use-site positions.
        if (expression instanceof Sig || expression instanceof ExprVar) return;
        Range span = range(expression.span());
        if (insideBody(span)) add(expressions, tokens(slice(span)), span);
        if (expression instanceof ExprUnary unary) visit(unary.sub);
        else if (expression instanceof ExprBinary binary) { visit(binary.left); visit(binary.right); }
        else if (expression instanceof ExprList list) for (Expr child : list.args) visit(child);
        else if (expression instanceof ExprCall call) {
            // Never descend into the called helper's body or declaration.
            for (Expr argument : call.args) visit(argument);
        } else if (expression instanceof ExprQt quantifier) {
            addBindings(quantifier);
            for (Decl declaration : quantifier.decls) visit(declaration.expr);
            visit(quantifier.sub);
        } else if (expression instanceof ExprLet let) { visit(let.expr); visit(let.sub); }
        else if (expression instanceof ExprITE conditional) {
            visit(conditional.cond); visit(conditional.left); visit(conditional.right);
        }
    }

    private void addBindings(ExprQt quantifier) {
        Range operator = range(quantifier.pos);
        if (!insideBody(operator)) return;
        int end = operator.end;
        for (Decl declaration : quantifier.decls) {
            Range span = range(declaration.span());
            if (!insideBody(span)) return;
            end = Math.max(end, span.end);
        }
        Range header = new Range(operator.start, end);
        Range original = range(quantifier.span());
        if (original != null) bindingHeaders.put(original, header);
        for (Decl declaration : quantifier.decls) {
            Range bound = range(declaration.expr.span());
            if (!insideBody(bound)) continue;
            List<String> boundTokens = tokens(slice(bound));
            if (boundTokens.isEmpty()) continue;
            String cardinality = "one";
            if (declaration.expr instanceof ExprUnary unary) {
                cardinality = switch (unary.op) {
                    case SOMEOF -> "some";
                    case LONEOF -> "lone";
                    case SETOF -> "set";
                    case EXACTLYOF -> "exactly";
                    default -> "one";
                };
            }
            boolean explicitCardinality = boundTokens.get(0).equals(cardinality);
            for (ExprHasName name : declaration.names) {
                String term = quantifier.op.name().toLowerCase(Locale.ROOT)
                        + (declaration.disjoint != null ? " disj " : " ") + name.label
                        + " : " + (explicitCardinality ? "" : cardinality + " ") + slice(bound);
                add(bindings, tokens(term), header);
            }
        }
    }

    private static void add(Map<List<String>, List<Range>> index, List<String> key, Range range) {
        if (!key.isEmpty()) index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(range);
    }

    private String slice(Range span) { return source.substring(span.start, span.end); }
    private boolean insideBody(Range span) { return span != null && body != null && span.start >= body.start && span.end <= body.end; }

    private Range range(Pos position) {
        if (position == null || !position.filename.equals(filename) || position.y < 1 || position.y2 < position.y
                || position.y2 > lineStarts.size() || position.x < 1 || position.x2 < 1) return null;
        int start = lineStarts.get(position.y - 1) + position.x - 1;
        int end = lineStarts.get(position.y2 - 1) + position.x2;
        // Alloy's columns and Java's offsets count UTF-16 code units, including
        // tabs as one unit. End columns are inclusive; public ends are exclusive.
        int firstLineEnd = position.y < lineStarts.size() ? lineStarts.get(position.y) : source.length();
        int lastLineEnd = position.y2 < lineStarts.size() ? lineStarts.get(position.y2) : source.length();
        if (start < 0 || start >= firstLineEnd || end <= start || end > lastLineEnd) return null;
        return new Range(start, end);
    }

    /** A token sequence, not substring matching. Comments cannot become matches.
     * Grouping is ignored only to suggest related expressions; we make no claim
     * that this sequence proves AST identity or canonical occurrence provenance.
     */
    private static List<String> tokens(String value) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || "(){}".indexOf(c) >= 0) { i++; continue; }
            if (value.startsWith("//", i) || value.startsWith("--", i)) {
                int end = value.indexOf('\n', i + 2); i = end < 0 ? value.length() : end + 1; continue;
            }
            if (value.startsWith("/*", i)) {
                int end = value.indexOf("*/", i + 2); i = end < 0 ? value.length() : end + 2; continue;
            }
            if (c == '"') {
                int end = i + 1;
                while (end < value.length()) {
                    if (value.charAt(end++) == '\\' && end < value.length()) { end++; continue; }
                    if (value.charAt(end - 1) == '"') break;
                }
                result.add(value.substring(i, end)); i = end; continue;
            }
            if (Character.isJavaIdentifierStart(c) || Character.isDigit(c)) {
                int end = i + 1;
                while (end < value.length() && Character.isJavaIdentifierPart(value.charAt(end))) end++;
                result.add(value.substring(i, end)); i = end; continue;
            }
            String token = null;
            for (String candidate : List.of("<=>", ">>>", "!>=", "!<=", "!in", "=>", "&&", "||", "!=", "!>", "!<", ">=", "<=", "->", "<:", ":>", "++", "<<", ">>")) {
                if (value.startsWith(candidate, i)) { token = candidate; break; }
            }
            if (token == null) token = String.valueOf(c);
            i += token.length();
            result.add(switch (token) { case "&&" -> "and"; case "||" -> "or"; case "!" -> "not"; case "=>" -> "implies"; case "<=>" -> "iff"; default -> token; });
        }
        return List.copyOf(result);
    }

    private static JSONObject unavailable(String reason) {
        return metadata("unavailable", "related", reason).put("ranges", new JSONArray());
    }

    private static JSONObject metadata(String status, String precision, String reason) {
        return new JSONObject().put("status", status).put("precision", precision)
                .put("coordinateSystem", "module").put("offsetEncoding", "utf-16").put("reason", reason);
    }
}
