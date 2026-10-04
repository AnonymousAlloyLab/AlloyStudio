package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.theory.IndependentCertificateVerifier;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

/** Producer-backed representative source export and independent-verifier census. */
public final class CertificateVerifierExportSmoke {
    private static final String SOURCE = """
            module certificate_smoke

            sig A {}

            pred nullary {}
            pred slotBearing[x: A] { x = x }
            pred deliberatelyUnsupported[x, y: A] {
              (x = y or x != y) and (y = x or y != x)
            }
            """;
    private static final String PAIR_LEFT_SOURCE = """
            module parsed_pair_left

            pred left {}
            """;
    private static final String PAIR_RIGHT_SOURCE = """
            module parsed_pair_right

            pred right {}
            """;

    private CertificateVerifierExportSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: CertificateVerifierExportSmoke "
                            + "<output-dir> <trusted-theory-digest>");
        }
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        Path verifierJar = configuredVerifierJar();
        String trustedTheoryDigest = args[1];
        if (!trustedTheoryDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("trusted theory digest must be SHA-256");
        }
        Parsed parsed = parseRepresentativeModel();
        List<Case> cases = List.of(
                new Case("nullary", "nullary construction"),
                new Case("slotBearing", "slot-bearing construction"),
                new Case("deliberatelyUnsupported", "deliberately unsupported construction"));
        List<Result> results = new ArrayList<>();
        for (Case testCase : cases) {
            results.add(export(
                    parsed, testCase, output, verifierJar, trustedTheoryDigest));
        }
        assertExactCensus(results);
        writeCensus(output.resolve("coverage-census.tsv"), results);
        exportParsedSourcePair(output, verifierJar, trustedTheoryDigest);
        long verified = results.stream().filter(value -> value.status.equals("VERIFIED")).count();
        long uncheckable = results.stream().filter(
                value -> value.status.equals("UNCHECKABLE")).count();
        long rejected = results.stream().filter(value -> value.status.equals("REJECTED")).count();
        System.out.println("certificate export census: total=" + results.size()
                + " VERIFIED=" + verified
                + " UNCHECKABLE=" + uncheckable
                + " REJECTED=" + rejected);
        for (Result result : results) {
            System.out.println(result.name + "\t" + result.status + "\t"
                    + result.code + "\t" + result.reason);
        }
        if (rejected != 0) {
            throw new IllegalStateException("Representative export census contains REJECTED cases");
        }
    }

    private static Result export(
            Parsed parsed,
            Case testCase,
            Path output,
            Path verifierJar,
            String trustedTheoryDigest) {
        Integer graphId = parsed.visitor.getForestId(testCase.name);
        DoubleMap<Integer, Multigraph> forest = parsed.visitor.getForest();
        Multigraph graph = graphId == null ? null : forest.get(graphId);
        if (graph == null) {
            return new Result(testCase.name, testCase.coverage, "REJECTED",
                    "MISSING_MASG", "MASG did not produce the selected predicate", "");
        }
        Path bundle = output.resolve(testCase.name + ".acgncert");
        try {
            CanonicalAlloyPipeline.Prepared prepared =
                    CanonicalAlloyPipeline.prepareCompatibilityForVerification(
                            graph,
                            "certificate-smoke.als#" + testCase.name,
                            SOURCE.getBytes(StandardCharsets.UTF_8));
            prepared.certificateExportSession().write(bundle);
            ProcessResult verification = verify(
                    verifierJar, bundle, trustedTheoryDigest);
            return new Result(
                    testCase.name,
                    testCase.coverage,
                    verification.outcome,
                    verification.code,
                    compact(verification.output),
                    bundle.getFileName().toString());
        } catch (UncheckedIOException exception) {
            return new Result(testCase.name, testCase.coverage, "REJECTED",
                    "EXPORT_IO_FAILURE", compact(exception.getMessage()), "");
        } catch (IOException exception) {
            String message = compact(exception.getMessage());
            String status = message.startsWith("UNCHECKABLE:")
                    ? "UNCHECKABLE" : "REJECTED";
            String code = status.equals("UNCHECKABLE")
                    ? "EXPORT_UNSUPPORTED" : "EXPORT_IO_FAILURE";
            return new Result(testCase.name, testCase.coverage, status, code, message, "");
        } catch (RuntimeException exception) {
            return new Result(testCase.name, testCase.coverage, "REJECTED",
                    "EXPORT_RUNTIME_FAILURE",
                    compact(exception.getClass().getSimpleName() + ": "
                            + exception.getMessage()), "");
        }
    }

    private static void exportParsedSourcePair(
            Path output,
            Path verifierJar,
            String trustedTheoryDigest) throws Exception {
        Path leftSource = output.resolve("parsed-pair-left.als");
        Path rightSource = output.resolve("parsed-pair-right.als");
        Files.writeString(leftSource, PAIR_LEFT_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(rightSource, PAIR_RIGHT_SOURCE, StandardCharsets.UTF_8);
        Path leftBundle = output.resolve("parsed-pair-left.acgncert");
        Path rightBundle = output.resolve("parsed-pair-right.acgncert");
        CanonicalAlloyPipeline.Prepared leftPrepared = exportParsedSource(
                parseSource(leftSource),
                "left",
                "fixture/parsed-pair-left.als#left",
                Files.readAllBytes(leftSource),
                leftBundle);
        CanonicalAlloyPipeline.Prepared rightPrepared = exportParsedSource(
                parseSource(rightSource),
                "right",
                "fixture/parsed-pair-right.als#right",
                Files.readAllBytes(rightSource),
                rightBundle);
        ProcessResult result = verifyPair(
                verifierJar, leftBundle, rightBundle, trustedTheoryDigest);
        if (result.exitCode != 0
                || !result.outcome.equals("VERIFIED")
                || !result.code.equals("NONE")) {
            throw new IllegalStateException(
                    "parsed source PAIR did not verify: " + compact(result.output));
        }

        String verifierDigest = sha256(Files.readAllBytes(verifierJar));
        IndependentCertificateVerifier.Policy policy =
                new IndependentCertificateVerifier.Policy(
                        verifierJar,
                        verifierDigest,
                        trustedTheoryDigest,
                        Duration.ofSeconds(30),
                        16L * 1024L * 1024L,
                        64 * 1024,
                        true,
                        callCommitments(verifierJar, leftBundle, rightBundle));
        CanonicalAlloyPipeline.StandaloneReplayDistance checked =
                CanonicalAlloyPipeline.distanceEvaluationWithStandaloneReplay(
                        leftPrepared, rightPrepared, policy);
        if (checked.metric().distance() != 0
                || checked.scope()
                        != CanonicalAlloyPipeline.StandaloneReplayScope
                                .TEST_ONLY_NORMALIZED_IR_ZERO_KERNEL
                || !checked.pairResult().verified()) {
            throw new IllegalStateException(
                    "independent distance boundary did not certify the parsed zero pair");
        }
        expectIndependentFailure(
                leftPrepared,
                rightPrepared,
                new IndependentCertificateVerifier.Policy(
                        verifierJar,
                        verifierDigest,
                        trustedTheoryDigest,
                        Duration.ofSeconds(30),
                        16L * 1024L * 1024L,
                        64 * 1024,
                        false,
                        callCommitments(verifierJar, leftBundle, rightBundle)),
                "rejects test-only evidence");
        expectIndependentFailure(
                leftPrepared,
                rightPrepared,
                new IndependentCertificateVerifier.Policy(
                        verifierJar,
                        "0".repeat(64),
                        trustedTheoryDigest,
                        Duration.ofSeconds(30),
                        16L * 1024L * 1024L,
                        64 * 1024,
                        true),
                "digest");
    }

    private static CanonicalAlloyPipeline.Prepared exportParsedSource(
            Parsed parsed,
            String predicate,
            String inputIdentifier,
            byte[] source,
            Path output) throws IOException {
        Integer graphId = parsed.visitor.getForestId(predicate);
        Multigraph graph = graphId == null
                ? null : parsed.visitor.getForest().get(graphId);
        if (graph == null) {
            throw new IOException("parsed source fixture lacks predicate " + predicate);
        }
        CanonicalAlloyPipeline.Prepared prepared =
                CanonicalAlloyPipeline.prepareCompatibilityForVerification(
                        graph, inputIdentifier, source);
        prepared.certificateExportSession().write(output);
        return prepared;
    }

    private static void expectIndependentFailure(
            CanonicalAlloyPipeline.Prepared left,
            CanonicalAlloyPipeline.Prepared right,
            IndependentCertificateVerifier.Policy policy,
            String expected) throws Exception {
        try {
            CanonicalAlloyPipeline.distanceEvaluationWithStandaloneReplay(
                    left, right, policy);
            throw new AssertionError("Expected independent verification failure: " + expected);
        } catch (IllegalArgumentException | IOException exception) {
            if (!exception.getMessage().contains(expected)) {
                throw new AssertionError(
                        "Expected failure containing '" + expected + "' but found: "
                                + exception.getMessage(),
                        exception);
            }
        }
    }

    private static Parsed parseRepresentativeModel() throws Exception {
        Path directory = Files.createTempDirectory("acgn-certificate-smoke-");
        Path source = directory.resolve("certificate-smoke.als");
        try {
            Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
            return parseSource(source);
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(directory);
        }
    }

    private static Parsed parseSource(Path source) throws Exception {
        CompModule module = AlloyUtil.compileAlloyModule(source.toString());
        if (module == null) {
            throw new IllegalStateException("Alloy rejected source fixture " + source);
        }
        ModelUnit model = new ModelUnit(null, module);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(model, null);
        return new Parsed(visitor);
    }

    private static Path configuredVerifierJar() {
        String configured = System.getProperty("acgn.provenance.verifierJar", "");
        if (configured.isBlank()) {
            throw new IllegalStateException(
                    "acgn.provenance.verifierJar is required by the export smoke");
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Verifier JAR is missing: " + path);
        }
        return path;
    }

    private static ProcessResult verify(
            Path verifierJar,
            Path bundle,
            String trustedTheoryDigest) throws IOException {
        String commitment = inspectCallCommitment(verifierJar, bundle);
        return runVerifier(new ProcessBuilder(
                javaExecutable(), "-jar", verifierJar.toString(),
                "--profile", "full", "--theory-digest", trustedTheoryDigest,
                "--call-occurrence-commitment", commitment,
                bundle.toString()));
    }

    private static ProcessResult verifyPair(
            Path verifierJar,
            Path left,
            Path right,
            String trustedTheoryDigest) throws IOException {
        String leftCommitment = inspectCallCommitment(verifierJar, left);
        String rightCommitment = inspectCallCommitment(verifierJar, right);
        return runVerifier(new ProcessBuilder(
                javaExecutable(), "-jar", verifierJar.toString(),
                "--profile", "pair", "--theory-digest", trustedTheoryDigest,
                "--call-occurrence-commitment", leftCommitment,
                "--call-occurrence-commitment", rightCommitment,
                left.toString(), right.toString()));
    }

    /** TEST_ONLY smoke plumbing; inspected bundle data is not production authority. */
    private static java.util.Map<String, String> callCommitments(
            Path verifierJar,
            Path... bundles) throws IOException {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Path bundle : bundles) {
            String assignment = inspectCallCommitment(verifierJar, bundle);
            int separator = assignment.indexOf('=');
            String subject = assignment.substring(0, separator);
            String digest = assignment.substring(separator + 1);
            String prior = result.putIfAbsent(subject, digest);
            if (prior != null && !prior.equals(digest)) {
                throw new IOException(
                        "TEST_ONLY fixtures conflict on one CALL commitment subject");
            }
        }
        return java.util.Map.copyOf(result);
    }

    private static String inspectCallCommitment(
            Path verifierJar,
            Path bundle) throws IOException {
        Process process = new ProcessBuilder(
                javaExecutable(), "-jar", verifierJar.toString(),
                "--inspect-call-occurrences", bundle.toString())
                .redirectErrorStream(true)
                .start();
        try {
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0
                    || !output.matches("[0-9a-f]{64}=[0-9a-f]{64}")) {
                throw new IOException(
                        "TEST_ONLY CALL commitment inspection failed: "
                                + compact(output));
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "TEST_ONLY CALL commitment inspection was interrupted",
                    exception);
        }
    }

    private static ProcessResult runVerifier(ProcessBuilder builder) throws IOException {
        Process verifyProcess = builder.redirectErrorStream(true).start();
        try {
            String output = new String(
                    verifyProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = verifyProcess.waitFor();
            return new ProcessResult(
                    exitCode,
                    jsonField(output, "outcome"),
                    jsonField(output, "code"),
                    output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Verifier smoke was interrupted", exception);
        }
    }

    private static String jsonField(String json, String name) throws IOException {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IOException("verifier output lacks " + name + ": " + compact(json));
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            throw new IOException("verifier output has malformed " + name);
        }
        return json.substring(start, end);
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void writeCensus(Path output, List<Result> results) throws IOException {
        StringBuilder text = new StringBuilder(
                "predicate\tcoverage\tstatus\tcode\treason\tbundle\n");
        for (Result result : results) {
            text.append(result.name).append('\t')
                    .append(result.coverage).append('\t')
                    .append(result.status).append('\t')
                    .append(result.code).append('\t')
                    .append(result.reason.replace('\t', ' ')).append('\t')
                    .append(result.bundle).append('\n');
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static void assertExactCensus(List<Result> results) {
        java.util.Map<String, String> expected = java.util.Map.of(
                "nullary", "VERIFIED\tNONE",
                "slotBearing", "UNCHECKABLE\tEXPORT_UNSUPPORTED",
                "deliberatelyUnsupported", "UNCHECKABLE\tEXPORT_UNSUPPORTED");
        if (results.size() != expected.size()) {
            throw new IllegalStateException("certificate census size changed");
        }
        for (Result result : results) {
            String actual = result.status + "\t" + result.code;
            if (!actual.equals(expected.get(result.name))) {
                throw new IllegalStateException(
                        "certificate census changed for " + result.name
                                + ": expected=" + expected.get(result.name)
                                + " actual=" + actual);
            }
        }
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "no detail";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record Parsed(MASGVisitor visitor) {
    }

    private record Case(String name, String coverage) {
    }

    private record Result(
            String name,
            String coverage,
            String status,
            String code,
            String reason,
            String bundle) {
    }

    private record ProcessResult(
            int exitCode,
            String outcome,
            String code,
            String output) {
    }
}
