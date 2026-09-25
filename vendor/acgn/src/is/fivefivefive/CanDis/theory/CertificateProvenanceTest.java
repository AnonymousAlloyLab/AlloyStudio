package is.fivefivefive.CanDis.theory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Isolated Git-fixture checks for publication and explicit test provenance. */
public final class CertificateProvenanceTest {
    private CertificateProvenanceTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("acgn-provenance-test-");
        String priorRoot = System.getProperty("acgn.repo.root");
        String priorProducer = System.getProperty("acgn.provenance.producerJar");
        String priorVerifier = System.getProperty("acgn.provenance.verifierJar");
        String priorCreated = System.getProperty("acgn.provenance.createdAt");
        String priorOverride = System.getProperty("acgn.provenance.testOverride");
        try {
            Fixture fixture = createFixture(root);
            System.setProperty("acgn.repo.root", root.toString());
            System.setProperty("acgn.provenance.producerJar", fixture.producerJar.toString());
            System.setProperty("acgn.provenance.verifierJar", fixture.verifierJar.toString());
            System.setProperty("acgn.provenance.createdAt", "2026-08-19T00:00:00Z");
            System.clearProperty("acgn.provenance.testOverride");

            CertificateProvenance clean = CertificateProvenance.capture(
                    "fixture.als#p",
                    "pred p {}".getBytes(StandardCharsets.UTF_8),
                    "fixture=true");
            check(!clean.testOnly(), "clean repository produces publication provenance");
            check(clean.metadataScalars("components", "run").get(0)
                            .equals(command(root, "git", "rev-parse", "HEAD")),
                    "publication provenance records the actual fixture commit");

            Files.writeString(
                    fixture.source,
                    "final class Source { int dirty; }\n",
                    StandardCharsets.UTF_8);
            expectIOException(() -> CertificateProvenance.capture(
                    "fixture.als#p",
                    "pred p {}".getBytes(StandardCharsets.UTF_8),
                    "fixture=true"),
                    "dirty publication provenance must fail closed");

            System.setProperty("acgn.provenance.testOverride", "true");
            CertificateProvenance testOnly = CertificateProvenance.capture(
                    "fixture.als#p",
                    "pred p {}".getBytes(StandardCharsets.UTF_8),
                    "fixture=true");
            check(testOnly.testOnly(), "dirty fixture requires an explicit TEST_ONLY marker");
            check("true".equals(testOnly.metadataScalars("components", "run").get(1)),
                    "test-only provenance still records dirty state truthfully");
        } finally {
            restore("acgn.repo.root", priorRoot);
            restore("acgn.provenance.producerJar", priorProducer);
            restore("acgn.provenance.verifierJar", priorVerifier);
            restore("acgn.provenance.createdAt", priorCreated);
            restore("acgn.provenance.testOverride", priorOverride);
            deleteTree(root);
        }
        System.out.println("CertificateProvenanceTest passed");
    }

    private static Fixture createFixture(Path root) throws Exception {
        Path writer = root.resolve(
                "src/is/fivefivefive/CanDis/theory/CertificateBundleWriter.java");
        Path source = root.resolve("src/Source.java");
        Path verifierSource = root.resolve("certificate-verifier/src/Verifier.java");
        Path dependency = root.resolve("lib/dependency.jar");
        Path producerJar = root.resolve("build/producer.jar");
        Path verifierJar = root.resolve("build/verifier.jar");
        for (Path path : new Path[] {
                writer, source, verifierSource, dependency, producerJar, verifierJar
        }) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "fixture:" + path.getFileName() + "\n", StandardCharsets.UTF_8);
        }
        command(root, "git", "init", "-q");
        command(root, "git", "config", "user.name", "ACGN Provenance Test");
        command(root, "git", "config", "user.email", "provenance@example.invalid");
        command(root, "git", "add", ".");
        command(root, "git", "commit", "-q", "-m", "fixture");
        return new Fixture(source, producerJar, verifierJar);
    }

    private static String command(Path root, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IOException("Fixture command failed: " + output);
        }
        return output;
    }

    private static void expectIOException(ThrowingAction action, String message)
            throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // Expected fail-closed publication behavior.
        }
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private record Fixture(Path source, Path producerJar, Path verifierJar) {
    }
}
