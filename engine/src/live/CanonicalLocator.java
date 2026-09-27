package live;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presentation-only locations in the learner's rendered canonical forms.
 * These lexical correspondences do not certify canonical-node occurrence identity.
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
                ranges.length() == 1 ? "Matching fragment in the learner canonical form."
                        : "Several canonical fragments match; no unique displayed occurrence is known.").put("ranges", ranges);
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
                "Learner canonical-form context; this operation has no unique matching displayed fragment.").put("ranges", ranges);
    }

    private static JSONObject unavailable() {
        return metadata("unavailable", "form", "No existing learner canonical form is available for this operation.")
                .put("ranges", new JSONArray());
    }

    private static JSONObject metadata(String status, String precision, String reason) {
        return new JSONObject().put("status", status).put("precision", precision).put("coordinateSystem", "canonical")
                .put("offsetEncoding", "utf-16").put("reason", reason);
    }
}
