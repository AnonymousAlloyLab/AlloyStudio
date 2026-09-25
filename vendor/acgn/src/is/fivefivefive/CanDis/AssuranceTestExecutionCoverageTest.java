package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Proves that every DIRECT test reference is reached by a bounded entry point. */
public final class AssuranceTestExecutionCoverageTest {
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");
    private static final Pattern MAIN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\([^)]*\\)\\s*(?:throws\\s+[^\\{]+)?\\{");
    private static int checks;

    private AssuranceTestExecutionCoverageTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0])
                .toAbsolutePath().normalize();
        String entryPoints = String.join("\n", List.of(
                Files.readString(root.resolve("scripts/run_section3_assurance.sh")),
                Files.readString(root.resolve("scripts/run_bounded_ci_java_tests.sh")),
                Files.readString(root.resolve("scripts/run_certificate_bundle_writer_tests.sh")),
                Files.readString(root.resolve(".github/workflows/bounded-ci.yml"))));
        List<String> rows = Files.readAllLines(
                root.resolve("docs/section3-repair-audit/requirements-traceability.tsv"),
                StandardCharsets.UTF_8);
        String[] header = rows.get(0).split("\t", -1);
        int idColumn = column(header, "requirement_id");
        int testsColumn = column(header, "test_refs");
        int statusColumn = column(header, "conformance_status");
        Set<String> covered = new HashSet<>();

        for (int line = 1; line < rows.size(); line++) {
            String[] cells = rows.get(line).split("\t", -1);
            if (!cells[statusColumn].equals("DIRECT")) {
                continue;
            }
            for (String reference : cells[testsColumn].split(";")) {
                String[] parts = reference.split("#", 2);
                Path evidence = root.resolve(parts[0]).normalize();
                check(evidence.startsWith(root) && Files.isRegularFile(evidence),
                        cells[idColumn] + " test evidence must exist");
                if (parts[0].endsWith(".java")) {
                    String source = Files.readString(evidence, StandardCharsets.UTF_8);
                    Matcher packageName = PACKAGE.matcher(source);
                    check(packageName.find(), "Java test must declare a package: " + parts[0]);
                    String simple = evidence.getFileName().toString().replaceFirst("\\.java$", "");
                    String className = packageName.group(1) + "." + simple;
                    check(entryPoints.contains(className),
                            cells[idColumn] + " test class is not executed: " + className);
                    String symbol = parts.length == 2 ? parts[1] : "main";
                    if (!symbol.equals("main")) {
                        check(mainBody(source).matches(
                                        "(?s).*\\b" + Pattern.quote(symbol) + "\\s*\\(.*"),
                                cells[idColumn] + " test method is not called by main: "
                                        + reference);
                    }
                    covered.add(className);
                } else {
                    check(parts.length == 1,
                            "non-Java executable evidence cannot name a Java symbol");
                    check(entryPoints.contains(Path.of(parts[0]).getFileName().toString()),
                            cells[idColumn] + " test script is not executed: " + parts[0]);
                    covered.add(parts[0]);
                }
            }
        }
        check(!covered.isEmpty(), "DIRECT rows must have executable evidence");
        System.out.println("AssuranceTestExecutionCoverageTest passed: "
                + checks + " checks across " + covered.size() + " executables");
    }

    private static int column(String[] header, String name) {
        for (int index = 0; index < header.length; index++) {
            if (header[index].equals(name)) {
                return index;
            }
        }
        throw new IllegalStateException("Missing matrix column " + name);
    }

    private static String mainBody(String source) {
        Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            return "";
        }
        int start = main.end();
        int depth = 1;
        for (int index = start; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start, index);
            }
        }
        return "";
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
