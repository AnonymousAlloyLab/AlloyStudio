package is.fivefivefive.CanDis;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import is.fivefivefive.CanDis.core.egraph.JavaEgglog;
import is.fivefivefive.CanDis.theory.LeanVerifiedRewrite;

/** Mechanical, bidirectional Lean/Java traceability for immutable R0 rules. */
public final class RewriteRuleTraceability {
    private static final Path CATALOG = Path.of(
            "docs", "section3-repair-audit", "rewrite-rule-traceability.tsv");
    private static final List<String> HEADER = List.of(
            "rule_id", "scope", "rule", "baseline_name", "lean_refs",
            "java_refs", "test_refs", "status", "notes");
    private static final Pattern RULE_ID = Pattern.compile("R0-[A-Z]+-[0-9]{3}");
    private static final Pattern LEAN_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:theorem|lemma)\\s+([A-Za-z0-9_'.]+)\\b");
    private static final Pattern BANNED_LEAN = Pattern.compile(
            "(?m)\\b(?:sorry|admit|axiom|unsafe)\\b");

    private RewriteRuleTraceability() {
    }

    public static Assessment assess(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Map<String, Rule> rules = readCatalog(normalized.resolve(CATALOG));
        List<String> failures = new ArrayList<>();
        Set<String> baselineNames = new LinkedHashSet<>();
        Set<String> governedClasses = new LinkedHashSet<>();

        for (Rule rule : rules.values()) {
            if (!"PROVED_AND_CONNECTED".equals(rule.status)) {
                failures.add(rule.id + " status is " + rule.status);
            }
            if (!rule.baselineName.isBlank()
                    && !baselineNames.add(rule.baselineName)) {
                failures.add(rule.id + " duplicates baseline name "
                        + rule.baselineName);
            }
            validateLeanRefs(normalized, rule, failures);
            validateJavaRefs(normalized, rule, governedClasses, failures);
            validateTestRefs(normalized, rule, failures);
        }

        Set<String> runtimeBaseline = new LinkedHashSet<>(JavaEgglog.ruleNames());
        if (!baselineNames.equals(runtimeBaseline)) {
            failures.add("baseline rule inventory mismatch: catalog="
                    + baselineNames + " runtime=" + runtimeBaseline);
        }

        validateReverseAnnotations(rules, governedClasses, failures);
        return new Assessment(rules.size(), baselineNames.size(), failures);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        Assessment assessment = assess(root);
        System.out.print(assessment.render());
        if (!assessment.failures().isEmpty()) {
            System.exit(1);
        }
    }

    private static Map<String, Rule> readCatalog(Path catalog) throws IOException {
        if (!Files.isRegularFile(catalog)) {
            throw new IllegalStateException("Missing rewrite-rule catalog: " + catalog);
        }
        List<String> lines = Files.readAllLines(catalog, StandardCharsets.UTF_8);
        if (lines.isEmpty()
                || !HEADER.equals(Arrays.asList(lines.get(0).split("\\t", -1)))) {
            throw new IllegalStateException("Unexpected rewrite-rule catalog header");
        }
        Map<String, Rule> result = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cells = line.split("\\t", -1);
            if (cells.length != HEADER.size()) {
                throw new IllegalStateException("Rewrite-rule catalog line "
                        + (index + 1) + " has " + cells.length + " columns");
            }
            String id = cells[0].trim();
            if (!RULE_ID.matcher(id).matches()) {
                throw new IllegalStateException("Malformed rewrite rule ID: " + id);
            }
            Rule rule = new Rule(
                    id, cells[1].trim(), cells[2].trim(), cells[3].trim(),
                    split(cells[4]), split(cells[5]), split(cells[6]),
                    cells[7].trim(), cells[8].trim());
            if (rule.scope.isBlank() || rule.description.isBlank()
                    || rule.leanRefs.isEmpty() || rule.javaRefs.isEmpty()
                    || rule.testRefs.isEmpty() || rule.notes.isBlank()) {
                throw new IllegalStateException("Incomplete rewrite rule " + id);
            }
            if (result.put(id, rule) != null) {
                throw new IllegalStateException("Duplicate rewrite rule " + id);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Rewrite-rule catalog is empty");
        }
        return Collections.unmodifiableMap(result);
    }

    private static void validateLeanRefs(
            Path root,
            Rule rule,
            List<String> failures) throws IOException {
        Map<Path, Set<String>> declarationsByFile = new LinkedHashMap<>();
        for (String reference : rule.leanRefs) {
            Reference parsed = parseReference(reference, rule.id, "Lean", failures);
            if (parsed == null) {
                continue;
            }
            Path file = resolveInside(root, parsed.path, rule.id, failures);
            if (file == null || !Files.isRegularFile(file)) {
                failures.add(rule.id + " Lean file does not exist: " + parsed.path);
                continue;
            }
            Set<String> declarations = declarationsByFile.get(file);
            if (declarations == null) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher banned = BANNED_LEAN.matcher(source);
                if (banned.find()) {
                    failures.add(rule.id + " Lean file contains banned token "
                            + banned.group() + ": " + parsed.path);
                }
                declarations = new LinkedHashSet<>();
                Matcher matcher = LEAN_DECLARATION.matcher(
                        eraseCommentsAndLiterals(source, false));
                while (matcher.find()) {
                    declarations.add(matcher.group(1));
                }
                declarationsByFile.put(file, declarations);
            }
            String local = parsed.symbol.substring(
                    parsed.symbol.lastIndexOf('.') + 1);
            if (!declarations.contains(local)) {
                failures.add(rule.id + " missing Lean theorem " + reference);
            }
        }
    }

    private static void validateJavaRefs(
            Path root,
            Rule rule,
            Set<String> governedClasses,
            List<String> failures) throws IOException {
        for (String reference : rule.javaRefs) {
            Reference parsed = parseReference(reference, rule.id, "Java", failures);
            if (parsed == null) {
                continue;
            }
            Path file = resolveInside(root, parsed.path, rule.id, failures);
            if (file == null || !Files.isRegularFile(file)) {
                failures.add(rule.id + " Java file does not exist: " + parsed.path);
                continue;
            }
            String className = className(parsed.path);
            governedClasses.add(className);
            Method method = annotatedMethod(className, parsed.symbol, rule.id, failures);
            if (method == null) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!declaresJavaMethod(source, parsed.symbol)) {
                failures.add(rule.id + " Java source declaration not found: " + reference);
            }
        }
    }

    private static Method annotatedMethod(
            String className,
            String methodName,
            String ruleId,
            List<String> failures) {
        try {
            List<Method> named = Arrays.stream(Class.forName(className).getDeclaredMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .toList();
            if (named.isEmpty()) {
                failures.add(ruleId + " compiled Java method not found: "
                        + className + "#" + methodName);
                return null;
            }
            for (Method method : named) {
                LeanVerifiedRewrite annotation = method.getAnnotation(
                        LeanVerifiedRewrite.class);
                if (annotation != null
                        && Arrays.asList(annotation.value()).contains(ruleId)) {
                    return method;
                }
            }
            failures.add(ruleId + " Java method lacks matching @LeanVerifiedRewrite: "
                    + className + "#" + methodName);
            return null;
        } catch (ClassNotFoundException exception) {
            failures.add(ruleId + " compiled Java class not found: " + className);
            return null;
        }
    }

    private static void validateTestRefs(
            Path root,
            Rule rule,
            List<String> failures) throws IOException {
        for (String reference : rule.testRefs) {
            Reference parsed = parseReference(reference, rule.id, "test", failures);
            if (parsed == null) {
                continue;
            }
            Path file = resolveInside(root, parsed.path, rule.id, failures);
            if (file == null || !Files.isRegularFile(file)) {
                failures.add(rule.id + " test file does not exist: " + parsed.path);
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!declaresJavaMethod(source, parsed.symbol)) {
                failures.add(rule.id + " test declaration not found: " + reference);
            }
        }
    }

    private static void validateReverseAnnotations(
            Map<String, Rule> rules,
            Set<String> governedClasses,
            List<String> failures) {
        for (String className : governedClasses) {
            try {
                for (Method method : Class.forName(className).getDeclaredMethods()) {
                    LeanVerifiedRewrite annotation = method.getAnnotation(
                            LeanVerifiedRewrite.class);
                    if (annotation == null) {
                        continue;
                    }
                    if (annotation.value().length == 0) {
                        failures.add(className + "#" + method.getName()
                                + " has an empty @LeanVerifiedRewrite");
                    }
                    Set<String> ids = new LinkedHashSet<>();
                    for (String id : annotation.value()) {
                        if (!ids.add(id)) {
                            failures.add(className + "#" + method.getName()
                                    + " repeats rule " + id);
                        }
                        Rule rule = rules.get(id);
                        if (rule == null) {
                            failures.add(className + "#" + method.getName()
                                    + " names uncatalogued rule " + id);
                            continue;
                        }
                        String expected = sourcePath(className) + "#" + method.getName();
                        if (!rule.javaRefs.contains(expected)) {
                            failures.add(className + "#" + method.getName()
                                    + " annotation is not referenced back by " + id);
                        }
                    }
                }
            } catch (ClassNotFoundException exception) {
                failures.add("governed Java class is unavailable: " + className);
            }
        }
    }

    private static Reference parseReference(
            String encoded,
            String ruleId,
            String kind,
            List<String> failures) {
        int separator = encoded.lastIndexOf('#');
        if (separator <= 0 || separator == encoded.length() - 1) {
            failures.add(ruleId + " malformed " + kind + " reference: " + encoded);
            return null;
        }
        return new Reference(encoded.substring(0, separator),
                encoded.substring(separator + 1));
    }

    private static Path resolveInside(
            Path root,
            String relative,
            String ruleId,
            List<String> failures) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            failures.add(ruleId + " reference escapes repository: " + relative);
            return null;
        }
        return resolved;
    }

    private static String className(String path) {
        if (!path.startsWith("src/") || !path.endsWith(".java")) {
            throw new IllegalStateException("Java reference must be under src/: " + path);
        }
        return path.substring("src/".length(), path.length() - ".java".length())
                .replace('/', '.');
    }

    private static String sourcePath(String className) {
        return "src/" + className.replace('.', '/') + ".java";
    }

    private static boolean declaresJavaMethod(String source, String method) {
        String code = eraseCommentsAndLiterals(source, true);
        return Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(")
                .matcher(code).find();
    }

    private static List<String> split(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : encoded.split(";", -1)) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !values.contains(trimmed)) {
                values.add(trimmed);
            }
        }
        return Collections.unmodifiableList(values);
    }

    private static String eraseCommentsAndLiterals(String source, boolean eraseStrings) {
        StringBuilder result = new StringBuilder(source.length());
        boolean lineComment = false;
        int blockDepth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    result.append('\n');
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (blockDepth > 0) {
                if (current == '/' && next == '-') {
                    blockDepth++;
                    result.append("  ");
                    index++;
                } else if (current == '-' && next == '/') {
                    blockDepth--;
                    result.append("  ");
                    index++;
                } else if (current == '*' && next == '/') {
                    blockDepth--;
                    result.append("  ");
                    index++;
                } else {
                    result.append(current == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (quote != 0) {
                if (!eraseStrings) {
                    result.append(current);
                } else {
                    result.append(current == '\n' ? '\n' : ' ');
                }
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                result.append("  ");
                index++;
            } else if ((current == '/' && next == '*')
                    || (current == '/' && next == '-')) {
                blockDepth = 1;
                result.append("  ");
                index++;
            } else if (current == '"' || current == '\'') {
                quote = current;
                result.append(eraseStrings ? ' ' : current);
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    public record Assessment(int rules, int baselineRules, List<String> failures) {
        public Assessment {
            failures = List.copyOf(failures);
        }

        public String render() {
            StringBuilder output = new StringBuilder()
                    .append("R0 rewrite-rule traceability\n")
                    .append("rules=").append(rules).append('\n')
                    .append("baselineRules=").append(baselineRules).append('\n')
                    .append("failures=").append(failures.size()).append('\n');
            for (String failure : failures) {
                output.append("FAIL\t").append(failure).append('\n');
            }
            return output.toString();
        }
    }

    private record Rule(
            String id,
            String scope,
            String description,
            String baselineName,
            List<String> leanRefs,
            List<String> javaRefs,
            List<String> testRefs,
            String status,
            String notes) {
    }

    private record Reference(String path, String symbol) {
    }
}
