package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed registration gate for the eighteen required Section 3 policies. */
public final class RequiredPolicyCoverageTest {
    private static final String HEADER = String.join("\t", List.of(
            "policy_id", "requirement", "test_path", "test_class",
            "test_symbol", "expected_outcome"));
    private static int checks;

    private RequiredPolicyCoverageTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0])
                .toAbsolutePath().normalize();
        Path census = root.resolve(
                "docs/section3-repair-audit/required-policy-coverage.tsv");
        List<String> lines = Files.readAllLines(census, StandardCharsets.UTF_8);
        check(!lines.isEmpty() && lines.get(0).equals(HEADER),
                "policy census header must be exact");
        check(lines.size() == 19, "policy census must contain exactly eighteen rows");
        String boundedRunner = Files.readString(
                root.resolve("scripts/run_bounded_ci_java_tests.sh"),
                StandardCharsets.UTF_8);
        String workflow = Files.readString(
                root.resolve(".github/workflows/bounded-ci.yml"),
                StandardCharsets.UTF_8);
        check(workflow.contains("./scripts/run_bounded_ci_java_tests.sh"),
                "GitHub Actions must execute the bounded policy entry point");

        Set<Integer> ids = new HashSet<>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            String[] cells = lines.get(lineNumber).split("\t", -1);
            check(cells.length == 6, "policy row must have six fields");
            int id = Integer.parseInt(cells[0]);
            check(id >= 1 && id <= 18 && ids.add(id),
                    "policy IDs must be the unique closed interval 1..18");
            check(!cells[1].isBlank() && !cells[5].isBlank(),
                    "policy and expected outcome must be explicit");
            Path testPath = root.resolve(cells[2]).normalize();
            check(testPath.startsWith(root) && Files.isRegularFile(testPath),
                    "registered policy test source must exist");
            String source = Files.readString(testPath, StandardCharsets.UTF_8);
            check(Pattern.compile("\\b" + Pattern.quote(cells[4]) + "\\s*\\(")
                            .matcher(source).find(),
                    "registered test symbol must exist: " + cells[4]);
            if (!cells[4].equals("main")) {
                check(mainBody(source).contains(cells[4] + "("),
                        "registered policy method must be called by main: " + cells[4]);
            }
            check(boundedRunner.contains(cells[3]),
                    "bounded CI must execute policy owner " + cells[3]);
        }
        check(ids.size() == 18, "every required policy must be registered once");
        System.out.println("RequiredPolicyCoverageTest passed: "
                + checks + " checks over 18 policies");
    }

    private static String mainBody(String source) {
        int main = source.indexOf("void main(");
        if (main < 0) {
            return "";
        }
        int start = source.indexOf('{', main);
        int depth = 0;
        for (int index = start; index >= 0 && index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start + 1, index);
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
