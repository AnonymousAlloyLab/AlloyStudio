package live;

import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.QuantiVar;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bind metric paths to learner canonical occurrences using a checked renderer.
 * The production overload never chooses an occurrence by text matching.
 */
final class CanonicalLocator {
    private static final Pattern PHASE = Pattern.compile("^normalForm\\[([0-9]+)\\]\\.");
    private static final Set<String> UNARY = Set.of("NOT", "SOME", "NO", "LONE", "ONE", "SETOF", "EXACTLY",
            "TRANSPOSE", "RCLOSURE", "CLOSURE", "CARDINALITY", "CAST2INT", "CAST2SIGINT", "PRIME", "BEFORE",
            "HISTORICALLY", "ONCE", "ALWAYS", "EVENTUALLY", "AFTER");
    private static final Set<String> QUANTIFIER = Set.of("ALL", "SOME", "NO", "LONE", "ONE", "SUM");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("&&", "and"), Map.entry("||", "or"), Map.entry("=>", "implies"), Map.entry("<=>", "iff"),
            Map.entry("SETOF", "set"), Map.entry("TRANSPOSE", "~"), Map.entry("RCLOSURE", "*"),
            Map.entry("CLOSURE", "^"), Map.entry("CARDINALITY", "#"), Map.entry("CAST2INT", "int"), Map.entry("CAST2SIGINT", "Int"));
    private static final Pattern TOKEN = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|[\\p{javaJavaIdentifierStart}0-9][\\p{javaJavaIdentifierPart}]*|<=>|>>>|!>=|!<=|!in|=>|&&|\\|\\||!=|!>|!<|>=|<=|->|<:|:>|\\+\\+|<<|>>|[^\\s]");
    private record Token(String text, int start, int end) { }
    private record Group(int start, int end) { }
    private CanonicalLocator() { }

    private static final Pattern MATRIX_PATH = Pattern.compile("^normalForm\\[([0-9]+)\\]\\.matrix(?:\\.child\\[[0-9]+\\])*$");
    private static final Pattern BINDING_PATH = Pattern.compile("^normalForm\\[([0-9]+)\\]\\.quantifier\\[([0-9]+)\\]$");
    private static final Method NORMALIZED = method(Canonical.Prepared.class, "normalizedForms");
    private static final Method FORM_LABEL = method(CanonicalDistance.class, "normalFormPath", NormalForm.class, int.class);
    private static final Method NODE_RENDER = method(CanonicalDistance.class, "eGraphFormula", EGraphNode.class);
    private static final Method BINDING_RENDER = method(CanonicalDistance.class, "quantifierFormula", QuantiVar.class);
    private static final Method BINDING_ORDER = method(CanonicalDistance.class, "canonicalQuantifierOrder", List.class);
    private record Span(int start, int end) { }
    private record FormIndex(Map<String, Span> matrix, List<Span> bindings) { }

    /** Shared read-only access to the same learner graph used by LiveTrace. */
    @SuppressWarnings("unchecked")
    static List<NormalForm> normalizedForms(Canonical.Prepared learner) {
        return (List<NormalForm>) invoke(NORMALIZED, learner);
    }

    static void attach(Canonical.Prepared learner, JSONArray forms, JSONArray operations) {
        List<FormIndex> indexes = new ArrayList<>();
        try {
            List<NormalForm> normalized = normalizedForms(learner);
            if (normalized.size() != forms.length()) throw new IllegalArgumentException();
            for (int phase = 0; phase < normalized.size(); phase++) {
                try { indexes.add(index(normalized.get(phase), phase, forms.getString(phase))); }
                catch (RuntimeException ignored) { indexes.add(null); }
            }
        } catch (RuntimeException ignored) {
            for (int i = 0; i < operations.length(); i++) operations.getJSONObject(i).put("canonicalLocation", unavailable());
            return;
        }
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i);
            JSONObject location;
            try { location = structuralLocation(indexes, forms, operation); }
            catch (RuntimeException ignored) { location = unavailable(); }
            operation.put("canonicalLocation", location);
        }
    }

    private static JSONObject structuralLocation(List<FormIndex> indexes, JSONArray forms, JSONObject operation) {
        String component = operation.optString("component");
        String path = operation.optString("path");
        if (operation.optBoolean("aggregate") || component.equals("temporal")) {
            List<Integer> phases = new ArrayList<>();
            Matcher phase = PHASE.matcher(path);
            if (phase.find()) {
                int number = Integer.parseInt(phase.group(1));
                if (number >= forms.length() || indexes.get(number) == null) return unavailable();
                phases.add(number);
            } else {
                for (int i = 0; i < forms.length(); i++) if (indexes.get(i) != null) phases.add(i);
            }
            if (phases.isEmpty() || phases.size() > 16) return unavailable();
            return formContext(forms, phases);
        }
        Matcher matrix = MATRIX_PATH.matcher(path);
        if (component.equals("matrix") && matrix.matches()) {
            int phase = Integer.parseInt(matrix.group(1));
            if (phase >= indexes.size() || indexes.get(phase) == null) return unavailable();
            Span span = indexes.get(phase).matrix.get(path);
            if (span == null) return unavailable();
            boolean anchor = operation.optString("sourceRole").equals("insertion-anchor");
            return nodeLocation(phase, span, anchor
                    ? "This is the existing canonical expression used as the insertion anchor."
                    : "This is the canonical occurrence selected by the edit step.");
        }
        Matcher binding = BINDING_PATH.matcher(path);
        if (component.equals("quantifier") && binding.matches()) {
            int phase = Integer.parseInt(binding.group(1));
            if (phase >= indexes.size() || indexes.get(phase) == null) return unavailable();
            // An inserted binding is absent from the learner. Its metric index
            // denotes an insertion gap, not an existing affected declaration.
            int number = Integer.parseInt(binding.group(2));
            List<Span> bindings = indexes.get(phase).bindings;
            if (operation.optString("kind").equals("insert"))
                return number <= bindings.size() ? formContext(forms, List.of(phase)) : unavailable();
            if (number >= bindings.size()) return unavailable();
            return nodeLocation(phase, bindings.get(number), "This is the canonical declaration selected by the edit step.");
        }
        return unavailable();
    }

    private static JSONObject nodeLocation(int phase, Span span, String reason) {
        return metadata("located", "node", reason).put("ranges", new JSONArray().put(new JSONObject()
                .put("formIndex", phase).put("start", span.start).put("end", span.end)));
    }

    @SuppressWarnings("unchecked")
    private static FormIndex index(NormalForm form, int phase, String authoritative) {
        StringBuilder rendered = new StringBuilder((String) invoke(FORM_LABEL, null, form, phase)).append(" := ");
        List<QuantiVar> printedOrder = form.getMatrixQuantiVars();
        List<Span> printedBindings = new ArrayList<>();
        for (int i = 0; i < printedOrder.size(); i++) {
            if (i > 0) rendered.append(' ');
            int start = rendered.length();
            rendered.append((String) invoke(BINDING_RENDER, null, printedOrder.get(i)));
            printedBindings.add(new Span(start, rendered.length()));
        }
        if (!printedOrder.isEmpty()) rendered.append(" . ");
        Map<String, Span> matrix = new LinkedHashMap<>();
        renderNode(form.getMatrixEGraph(), "normalForm[" + phase + "].matrix", rendered, matrix,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        // This guards every formatter rule and prefix against framework drift.
        // Never trust reconstructed offsets unless the complete rendered form
        // agrees byte-for-byte (Java UTF-16 code units) with authoritative output.
        if (!rendered.toString().equals(authoritative)) throw new IllegalStateException();
        List<QuantiVar> metricOrder = (List<QuantiVar>) invoke(BINDING_ORDER, null, printedOrder);
        List<Span> metricBindings = new ArrayList<>();
        boolean[] consumed = new boolean[printedOrder.size()];
        for (QuantiVar binding : metricOrder) {
            int found = -1;
            for (int i = 0; i < printedOrder.size(); i++) {
                if (!consumed[i] && printedOrder.get(i) == binding) { found = i; break; }
            }
            if (found < 0) throw new IllegalStateException();
            consumed[found] = true;
            metricBindings.add(printedBindings.get(found));
        }
        return new FormIndex(matrix, metricBindings);
    }

    /** Mirror the pinned grammar, retaining each child edge's occurrence path. */
    private static void renderNode(EGraphNode node, String path, StringBuilder text,
            Map<String, Span> spans, Set<EGraphNode> active) {
        if (node == null) { text.append("<empty>"); return; }
        if (!active.add(node)) throw new IllegalStateException();
        int start = text.length();
        List<EGraphNode> children = node.getChildren();
        switch (node.getOpcode()) {
            case VARIABLE, GLOBALBINDING, CONSTANT -> text.append((String) invoke(NODE_RENDER, null, node));
            case TEMPORALROOT -> {
                if (children.size() == 1) renderChild(node, 0, path, text, spans, active);
                else renderOperator(node, path, text, spans, active);
            }
            case NOT, SOME, NO, LONE, ONE, SETOF, EXACTLY, TRANSPOSE, RCLOSURE, CLOSURE,
                    CARDINALITY, CAST2INT, CAST2SIGINT, PRIME, BEFORE, HISTORICALLY, ONCE,
                    ALWAYS, EVENTUALLY, AFTER -> {
                if (children.isEmpty()) text.append(node.getOpcode());
                // Shared quantifier opcodes can retain declaration and body
                // children. Match the authoritative renderer's arity guard and
                // index every child occurrence, including the predicate body.
                else if (children.size() != 1) renderOperator(node, path, text, spans, active);
                else {
                    text.append('(').append(node.getOpcode()).append(' ');
                    renderChild(node, 0, path, text, spans, active);
                    text.append(')');
                }
            }
            case AND, OR, IMPLIES, IFF, EQUALS, NOT_EQUALS, IN, NOT_IN, GT, GTE, LT, LTE,
                    JOIN, ARROW, INTERSECT, PLUS, PLUSPLUS, MINUS, UNTIL, RELEASES, SINCE, TRIGGERED -> {
                if (children.isEmpty()) text.append(node.getOpcode());
                else {
                    text.append('(');
                    for (int i = 0; i < children.size(); i++) {
                        if (i > 0) text.append(' ').append(infix(node)).append(' ');
                        renderChild(node, i, path, text, spans, active);
                    }
                    text.append(')');
                }
            }
            case ITE -> {
                if (children.size() != 3) renderOperator(node, path, text, spans, active);
                else {
                    text.append("(if "); renderChild(node, 0, path, text, spans, active);
                    text.append(" then "); renderChild(node, 1, path, text, spans, active);
                    text.append(" else "); renderChild(node, 2, path, text, spans, active); text.append(')');
                }
            }
            default -> renderOperator(node, path, text, spans, active);
        }
        spans.put(path, new Span(start, text.length()));
        active.remove(node);
    }

    private static void renderChild(EGraphNode node, int index, String path, StringBuilder text,
            Map<String, Span> spans, Set<EGraphNode> active) {
        renderNode(node.getChildren().get(index), path + ".child[" + index + "]", text, spans, active);
    }

    private static void renderOperator(EGraphNode node, String path, StringBuilder text,
            Map<String, Span> spans, Set<EGraphNode> active) {
        String name = node.getSourceName();
        text.append(name == null || name.isEmpty() ? node.getOpcode().toString() : name);
        if (!node.getChildren().isEmpty()) {
            text.append('(');
            for (int i = 0; i < node.getChildren().size(); i++) {
                if (i > 0) text.append(", ");
                renderChild(node, i, path, text, spans, active);
            }
            text.append(')');
        }
    }

    private static String infix(EGraphNode node) {
        return switch (node.getOpcode()) {
            case AND -> "&&"; case OR -> "||"; case IMPLIES -> "=>"; case IFF -> "<=>";
            case EQUALS -> "="; case NOT_EQUALS -> "!="; case IN -> "in"; case NOT_IN -> "!in";
            case GT -> ">"; case GTE -> ">="; case LT -> "<"; case LTE -> "<=";
            case JOIN -> "."; case ARROW -> "->"; case INTERSECT -> "&";
            case PLUS -> "+"; case PLUSPLUS -> "++"; case MINUS -> "-";
            case UNTIL -> "until"; case RELEASES -> "releases"; case SINCE -> "since"; case TRIGGERED -> "triggered";
            default -> throw new IllegalArgumentException();
        };
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method result = owner.getDeclaredMethod(name, parameters);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        if (method == null) throw new IllegalStateException();
        try { return method.invoke(target, arguments); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(); }
    }

    /** Legacy presentation-only overload for callers without a prepared graph. */

    static void attach(JSONArray forms, JSONArray operations) {
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i);
            JSONObject location;
            try { location = locate(forms, operation); }
            catch (RuntimeException ignored) { location = unavailable(); }
            operation.put("canonicalLocation", location);
        }
    }

    private static JSONObject locate(JSONArray forms, JSONObject operation) {
        List<Integer> phases = new ArrayList<>();
        Matcher phase = PHASE.matcher(operation.optString("path"));
        if (phase.find()) {
            int index;
            try { index = Integer.parseInt(phase.group(1)); }
            catch (NumberFormatException error) { return unavailable(); }
            if (index >= forms.length()) return unavailable();
            phases.add(index);
        } else for (int i = 0; i < forms.length(); i++) phases.add(i);
        if (phases.isEmpty() || phases.size() > 16) return unavailable();
        String term = operation.optString("sourceTerm", "");
        List<Token> needle = tokenize(term, false);
        JSONArray ranges = new JSONArray();
        if (!needle.isEmpty() && !term.endsWith("...")) {
            for (int formIndex : phases) {
                String form = forms.getString(formIndex);
                int formulaStart = form.indexOf(":=") + 2;
                List<Token> haystack = tokenize(form, true);
                for (int start = 0; start + needle.size() <= haystack.size(); start++) {
                    if (haystack.get(start).start < formulaStart) continue;
                    boolean matches = true;
                    for (int j = 0; j < needle.size(); j++) {
                        if (!haystack.get(start + j).text.equals(needle.get(j).text)) { matches = false; break; }
                    }
                    if (!matches) continue;
                    int first = haystack.get(start).start, last = haystack.get(start + needle.size() - 1).end;
                    // Include the smallest matching printed parenthesis group.
                    // Atomic/binding terms remain their token ranges.
                    if (term.startsWith("(")) {
                        for (Group group : groups(form)) {
                            if (group.start <= first && group.end >= last
                                    && sameTokens(tokenize(form.substring(group.start, group.end), true), needle)) {
                                first = group.start; last = group.end; break;
                            }
                        }
                    }
                    ranges.put(new JSONObject().put("formIndex", formIndex).put("start", first).put("end", last));
                    if (ranges.length() > 16) return formContext(forms, phases);
                }
            }
        }
        if (ranges.isEmpty()) return formContext(forms, phases);
        return metadata(ranges.length() == 1 ? "located" : "ambiguous", "related",
                ranges.length() == 1 ? "This part of your simplified predicate relates to the hint."
                        : "Several parts of your simplified predicate match this hint. Inspect each highlighted possibility.").put("ranges", ranges);
    }

    private static List<Token> tokenize(String text, boolean canonical) {
        List<Token> raw = new ArrayList<>(), result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) raw.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        for (int i = 0; i < raw.size(); i++) {
            Token token = raw.get(i);
            String value = token.text;
            if ("(){}".contains(value)) continue;
            boolean unary = canonical && UNARY.contains(value) && i > 0 && raw.get(i - 1).text.equals("(")
                    && i + 1 < raw.size() && !raw.get(i + 1).text.equals(")");
            boolean quantifier = canonical && QUANTIFIER.contains(value) && i + 2 < raw.size()
                    && (raw.get(i + 2).text.equals(":") || raw.get(i + 1).text.equals("disj"));
            if (unary || quantifier) value = ALIASES.getOrDefault(value, value.toLowerCase(java.util.Locale.ROOT));
            else if (ALIASES.containsKey(value) && !Character.isLetter(value.charAt(0))) value = ALIASES.get(value);
            result.add(new Token(value, token.start, token.end));
        }
        return result;
    }

    private static boolean sameTokens(List<Token> left, List<Token> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!left.get(i).text.equals(right.get(i).text)) return false;
        return true;
    }

    private static List<Group> groups(String form) {
        List<Integer> stack = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        boolean quoted = false, escaped = false;
        for (int i = 0; i < form.length(); i++) {
            char character = form.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
                continue;
            }
            if (character == '"') { quoted = true; continue; }
            if (form.charAt(i) == '(') stack.add(i);
            else if (form.charAt(i) == ')' && !stack.isEmpty()) groups.add(new Group(stack.remove(stack.size() - 1), i + 1));
        }
        groups.sort(java.util.Comparator.comparingInt(group -> group.end - group.start));
        return groups;
    }

    private static JSONObject formContext(JSONArray forms, List<Integer> phases) {
        JSONArray ranges = new JSONArray();
        for (int phase : phases) {
            String form = forms.getString(phase);
            if (!form.isEmpty()) ranges.put(new JSONObject().put("formIndex", phase).put("start", 0).put("end", form.length()));
        }
        return metadata(ranges.length() == 1 ? "located" : "ambiguous", "form",
                "The whole simplified predicate is shown because a smaller matching part could not be found.").put("ranges", ranges);
    }

    private static JSONObject unavailable() {
        return metadata("unavailable", "form", "There is no matching part of your simplified predicate to highlight.")
                .put("ranges", new JSONArray());
    }

    private static JSONObject metadata(String status, String precision, String reason) {
        return new JSONObject().put("status", status).put("precision", precision).put("coordinateSystem", "canonical")
                .put("offsetEncoding", "utf-16").put("reason", reason);
    }
}
