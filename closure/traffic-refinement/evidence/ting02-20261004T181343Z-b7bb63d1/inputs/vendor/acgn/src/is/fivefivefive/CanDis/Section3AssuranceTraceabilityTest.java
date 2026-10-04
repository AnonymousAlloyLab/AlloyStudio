package is.fivefivefive.CanDis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Section3AssuranceTraceabilityTest {
    private static int checks;

    private Section3AssuranceTraceabilityTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("acgn-assurance-trace-");
        try {
            createValidFixture(root);
            Section3AssuranceTraceability.Assessment valid =
                    Section3AssuranceTraceability.assess(root);
            check(valid.requirements() == 1, "one synthetic requirement is read");
            check(valid.rows() == 1, "one synthetic matrix row is read");
            check(valid.ready() == 0, "status labels cannot discharge missing decomposition");
            check(valid.failures().size() == 1
                            && valid.failures().get(0).contains("MISSING_LOW_LEVEL_REGISTRY"),
                    "otherwise complete mappings retain the exact missing A-01 obligation");

            Path decomposition = root.resolve(
                    "docs/section3-repair-audit/low-level-requirements.tsv");
            Files.writeString(decomposition,
                    "parent_id\tchild_id\tatomic\tproof\n"
                            + "A-01\tA-01.1\ttrue\tbounded_gate_rejects_missing\n",
                    StandardCharsets.UTF_8);
            expectFailure(root, "UNCHECKED_DECOMPOSITION");
            check(Section3AssuranceTraceability.assess(root).ready() == 0,
                    "a theorem-name registry cannot certify atomicity or reconstruction");
            Files.delete(decomposition);

            List<Path> fixtureRecords = List.of(
                    root.resolve("docs/section3-repair-audit/claim-ledger.md"),
                    root.resolve("docs/section3-repair-audit/assurance-scope.tsv"),
                    matrix(root));
            List<String> fixtureContents = new ArrayList<>();
            for (Path record : fixtureRecords) {
                String content = Files.readString(record, StandardCharsets.UTF_8);
                fixtureContents.add(content);
                Files.writeString(record, content.replace("A-01", "A-02"), StandardCharsets.UTF_8);
            }
            Section3AssuranceTraceability.Assessment otherRequirement =
                    Section3AssuranceTraceability.assess(root);
            check(otherRequirement.ready() == 1 && otherRequirement.failures().isEmpty(),
                    "the new A-01 evidence boundary does not reject unrelated requirement fixtures");
            for (int index = 0; index < fixtureRecords.size(); index++) {
                Files.writeString(fixtureRecords.get(index), fixtureContents.get(index), StandardCharsets.UTF_8);
            }

            Path generated = root.resolve("claims.md");
            Section3AssuranceTraceability.main(new String[] {
                    root.toString(), "--markdown-output=" + generated});
            String generatedText = Files.readString(generated, StandardCharsets.UTF_8);
            check(generatedText.endsWith("\n") && !generatedText.endsWith("\n\n"),
                    "the generated catalog has one final newline for byte-stable freshness checks");
            check(generatedText.contains("MISSING_LOW_LEVEL_REGISTRY") == false
                            && generatedText.contains("- Open diagnostics: 1"),
                    "generated summary retains the new unresolved obligation count");

            Path annotatedImplementation = root.resolve("src/Boundary.java");
            String plainImplementation = Files.readString(
                    annotatedImplementation, StandardCharsets.UTF_8);
            Files.writeString(annotatedImplementation,
                    "final class Boundary {\n"
                            + "  @Marker({\"R0-A\", nested(\"R0-B\")})\n"
                            + "  void rejectMissing() {}\n"
                            + "}\n",
                    StandardCharsets.UTF_8);
            Section3AssuranceTraceability.Assessment annotated =
                    Section3AssuranceTraceability.assess(root);
            check(annotated.failures().equals(valid.failures()),
                    "balanced Java annotations do not hide declarations");
            Files.writeString(annotatedImplementation, plainImplementation,
                    StandardCharsets.UTF_8);

            mutateAndExpect(root, 1, "claim hash mismatch",
                    row -> replaceCell(row, 1, "0".repeat(64)));
            mutateAndExpect(root, 1, "formal file does not exist",
                    row -> replaceCell(row, 2, "missing.lean"));
            mutateAndExpect(root, 1, "missing Lean declaration",
                    row -> replaceCell(row, 3, "not_the_theorem"));
            mutateAndExpect(root, 1, "missing Lean declaration",
                    row -> replaceCell(row, 3, "helperDefinition"));
            mutateAndExpect(root, 1, "has no implementation references",
                    row -> replaceCell(row, 4, "MISSING"));
            mutateAndExpect(root, 1, "implementation Java declaration not found",
                    row -> replaceCell(row, 4, "src/Boundary.java#absentSymbol"));
            mutateAndExpect(root, 1, "has no test references",
                    row -> replaceCell(row, 5, "MISSING"));
            mutateAndExpect(root, 1, "test Java declaration not found",
                    row -> replaceCell(row, 5, "src/BoundaryTest.java#absentTest"));
            Path callableTest = root.resolve("src/BoundaryTest.java");
            String callableSource = Files.readString(callableTest, StandardCharsets.UTF_8);
            Files.writeString(callableTest,
                    "final class BoundaryTest { int missingEvidenceRejects; }\n",
                    StandardCharsets.UTF_8);
            expectFailure(root, "test Java declaration not found");
            Files.writeString(callableTest, callableSource, StandardCharsets.UTF_8);
            mutateAndExpect(root, 1, "not an exact Java identifier",
                    row -> replaceCell(row, 4, "src/Boundary.java#reject missing"));
            mutateAndExpect(root, 1, "lacks ROBUSTNESS",
                    row -> replaceCell(row, 6, "NOMINAL+BOUNDARY"));
            mutateAndExpect(root, 1, "formal_status is MISSING",
                    row -> replaceCell(row, 7, "MISSING"));
            mutateAndExpect(root, 1, "conformance_status is PARTIAL",
                    row -> replaceCell(row, 8, "PARTIAL"));
            mutateAndExpect(root, 1, "escapes repository",
                    row -> replaceCell(row, 2, "../outside.lean"));

            Path formal = root.resolve("formal/Claim.lean");
            String originalFormal = Files.readString(formal, StandardCharsets.UTF_8);
            Files.writeString(formal,
                    originalFormal + "\n-- forbidden token: " + "sor" + "ry\n",
                    StandardCharsets.UTF_8);
            expectFailure(root, "contains banned token");
            Files.writeString(formal, originalFormal, StandardCharsets.UTF_8);

            Files.writeString(formal,
                    "-- theorem bounded_gate_rejects_missing : True := by trivial\n",
                    StandardCharsets.UTF_8);
            expectFailure(root, "missing Lean declaration");
            Files.writeString(formal,
                    "/- outer /- nested -/ theorem bounded_gate_rejects_missing "
                            + ": True := by trivial -/\n",
                    StandardCharsets.UTF_8);
            expectFailure(root, "missing Lean declaration");
            Files.writeString(formal, originalFormal, StandardCharsets.UTF_8);

            Path implementation = root.resolve("src/Boundary.java");
            String originalImplementation = Files.readString(
                    implementation, StandardCharsets.UTF_8);
            Files.writeString(implementation,
                    "final class Boundary { // void rejectMissing() {}\n}\n",
                    StandardCharsets.UTF_8);
            expectFailure(root, "implementation Java declaration not found");
            Files.writeString(implementation, originalImplementation,
                    StandardCharsets.UTF_8);

            Path test = root.resolve("src/BoundaryTest.java");
            String originalTest = Files.readString(test, StandardCharsets.UTF_8);
            Files.writeString(test,
                    "final class BoundaryTest { String x = \"missingEvidenceRejects\"; }\n",
                    StandardCharsets.UTF_8);
            expectFailure(root, "test Java declaration not found");
            Files.writeString(test, originalTest, StandardCharsets.UTF_8);

            Path matrix = matrix(root);
            String originalMatrix = Files.readString(matrix, StandardCharsets.UTF_8);
            String dataRow = originalMatrix.lines().skip(1).findFirst().orElseThrow();
            Files.writeString(matrix, originalMatrix + dataRow + "\n", StandardCharsets.UTF_8);
            expectThrows(root, "Duplicate matrix row");
            Files.writeString(matrix, originalMatrix, StandardCharsets.UTF_8);

            Files.writeString(matrix,
                    originalMatrix.lines().findFirst().orElseThrow() + "\n",
                    StandardCharsets.UTF_8);
            expectFailure(root, "MISSING requirement row");
            Files.writeString(matrix, originalMatrix, StandardCharsets.UTF_8);

            Path scope = root.resolve(
                    "docs/section3-repair-audit/assurance-scope.tsv");
            String originalScope = Files.readString(scope, StandardCharsets.UTF_8);
            Files.writeString(scope,
                    originalScope.replace("\tF/I\t", "\tP\t"),
                    StandardCharsets.UTF_8);
            expectFailure(root, "SCOPE ledger class/hash mismatch");
            Files.writeString(scope, originalScope, StandardCharsets.UTF_8);

            Files.writeString(scope,
                    originalScope.replace(sha256(
                            "The bounded gate rejects missing evidence."),
                            "0".repeat(64)),
                    StandardCharsets.UTF_8);
            expectFailure(root, "SCOPE ledger class/hash mismatch");
            Files.writeString(scope, originalScope, StandardCharsets.UTF_8);

            Files.delete(scope);
            expectThrows(root, "Missing assurance scope manifest");
            Files.writeString(scope, originalScope, StandardCharsets.UTF_8);

            Path ledger = root.resolve(
                    "docs/section3-repair-audit/claim-ledger.md");
            String originalLedger = Files.readString(ledger, StandardCharsets.UTF_8);
            Files.writeString(ledger,
                    originalLedger.replace("`PROVED/DIRECT`", "`BLOCKED`"),
                    StandardCharsets.UTF_8);
            expectFailure(root, "ledger state is BLOCKED; matrix state is PROVED/DIRECT");
            Files.writeString(ledger, originalLedger, StandardCharsets.UTF_8);

            Files.writeString(ledger,
                    originalLedger.replace(
                            "`PROVED/DIRECT` |", "`PROVED/DIRECT` | `BLOCKED` |"),
                    StandardCharsets.UTF_8);
            expectThrows(root, "must have exactly 6 columns");
            Files.writeString(ledger, originalLedger, StandardCharsets.UTF_8);

            Files.writeString(ledger,
                    originalLedger.replace("| A-01 |", "| A-1 |"),
                    StandardCharsets.UTF_8);
            expectThrows(root, "Malformed requirement ID");
            Files.writeString(ledger, originalLedger, StandardCharsets.UTF_8);

            Path realRoot = Path.of(args.length == 0 ? "." : args[0])
                    .toAbsolutePath().normalize();
            Section3AssuranceTraceability.Assessment repository =
                    Section3AssuranceTraceability.assess(realRoot);
            check(repository.requirements() == 191,
                    "repository matrix enumerates all 191 scoped requirements");
            check(repository.rows() == 191,
                    "repository has exactly one row per scoped requirement");
            check(repository.ready() >= 0
                            && repository.ready() <= repository.requirements(),
                    "repository readiness count remains within the exact scope");

            System.out.println("Section3AssuranceTraceabilityTest passed: "
                    + checks + " checks");
        } finally {
            deleteTree(root);
        }
    }

    private static void createValidFixture(Path root) throws Exception {
        Path audit = root.resolve("docs/section3-repair-audit");
        Files.createDirectories(audit);
        String claim = "The bounded gate rejects missing evidence.";
        Files.writeString(audit.resolve("claim-ledger.md"),
                "| ID | Class | Atomic claim | Formal obligation | Evidence | State |\n"
                        + "| --- | --- | --- | --- | --- | --- |\n"
                        + "| A-01 | F/I | " + claim
                        + " | theorem | test | `PROVED/DIRECT` |\n",
                StandardCharsets.UTF_8);
        Files.writeString(audit.resolve("assurance-scope.tsv"),
                "requirement_id\tclaim_class\tclaim_sha256\n"
                        + "A-01\tF/I\t" + sha256(claim) + "\n",
                StandardCharsets.UTF_8);

        Path formal = root.resolve("formal/Claim.lean");
        Files.createDirectories(formal.getParent());
        Files.writeString(formal,
                "def helperDefinition : Bool := true\n"
                        + "theorem bounded_gate_rejects_missing : True := by trivial\n",
                StandardCharsets.UTF_8);

        Path source = root.resolve("src/Boundary.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,
                "final class Boundary { void rejectMissing() {} }\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/BoundaryTest.java"),
                "final class BoundaryTest { void missingEvidenceRejects() {} }\n",
                StandardCharsets.UTF_8);

        String header = String.join("\t", List.of(
                "requirement_id", "claim_sha256", "formal_file",
                "formal_declarations", "implementation_refs", "test_refs",
                "test_classes", "formal_status", "conformance_status", "notes"));
        String row = String.join("\t", List.of(
                "A-01", sha256(claim), "formal/Claim.lean",
                "bounded_gate_rejects_missing", "src/Boundary.java#rejectMissing",
                "src/BoundaryTest.java#missingEvidenceRejects",
                "NOMINAL+BOUNDARY+ROBUSTNESS", "PROVED", "DIRECT", "fixture"));
        Files.writeString(matrix(root), header + "\n" + row + "\n",
                StandardCharsets.UTF_8);
    }

    private static void mutateAndExpect(
            Path root,
            int rowIndex,
            String fragment,
            RowMutation mutation) throws Exception {
        Path matrix = matrix(root);
        List<String> original = Files.readAllLines(matrix, StandardCharsets.UTF_8);
        List<String> changed = new ArrayList<>(original);
        changed.set(rowIndex, mutation.mutate(changed.get(rowIndex)));
        Files.write(matrix, changed, StandardCharsets.UTF_8);
        try {
            expectFailure(root, fragment);
        } finally {
            Files.write(matrix, original, StandardCharsets.UTF_8);
        }
    }

    private static String replaceCell(String row, int index, String value) {
        String[] cells = row.split("\\t", -1);
        cells[index] = value;
        return String.join("\t", cells);
    }

    private static void expectFailure(Path root, String fragment) throws IOException {
        Section3AssuranceTraceability.Assessment result =
                Section3AssuranceTraceability.assess(root);
        check(result.failures().stream().anyMatch(value -> value.contains(fragment)),
                "expected failure containing: " + fragment);
    }

    private static void expectThrows(Path root, String fragment) {
        try {
            Section3AssuranceTraceability.assess(root);
            throw new AssertionError("Expected exception containing: " + fragment);
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains(fragment),
                    "expected exception containing: " + fragment);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Path matrix(Path root) {
        return root.resolve("docs/section3-repair-audit/requirements-traceability.tsv");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte part : digest) {
            output.append(String.format("%02x", part & 0xff));
        }
        return output.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface RowMutation {
        String mutate(String row);
    }
}
