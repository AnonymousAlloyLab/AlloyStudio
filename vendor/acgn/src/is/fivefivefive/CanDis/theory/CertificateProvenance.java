package is.fivefivefive.CanDis.theory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Deterministic source, build, dependency, and input identity for one export.
 * Input identity is provenance metadata; it does not prove transformation of
 * those bytes into the normalized typed term certified by the bundle.
 */
public final class CertificateProvenance {
    public static final String EXPORTER_VERSION = "phase-j-producer-export-v3";
    public static final String VERIFIER_VERSION =
            "independent-certificate-verifier-v3-external-call-occurrences";
    private static final String TEST_OVERRIDE = "acgn.provenance.testOverride";

    private final String commit;
    private final boolean dirty;
    private final String mode;
    private final String javaSourceSha256;
    private final String producerJarSha256;
    private final String dependencyHashes;
    private final String inputIdentifier;
    private final String inputSha256;
    private final String exporterSourceSha256;
    private final String verifierSourceSha256;
    private final String verifierJarSha256;
    private final String configuration;
    private final String configurationSha256;
    private final String createdAt;

    private CertificateProvenance(
            String commit,
            boolean dirty,
            String mode,
            String javaSourceSha256,
            String producerJarSha256,
            String dependencyHashes,
            String inputIdentifier,
            String inputSha256,
            String exporterSourceSha256,
            String verifierSourceSha256,
            String verifierJarSha256,
            String configuration,
            String configurationSha256,
            String createdAt) {
        this.commit = commit;
        this.dirty = dirty;
        this.mode = mode;
        this.javaSourceSha256 = javaSourceSha256;
        this.producerJarSha256 = producerJarSha256;
        this.dependencyHashes = dependencyHashes;
        this.inputIdentifier = inputIdentifier;
        this.inputSha256 = inputSha256;
        this.exporterSourceSha256 = exporterSourceSha256;
        this.verifierSourceSha256 = verifierSourceSha256;
        this.verifierJarSha256 = verifierJarSha256;
        this.configuration = configuration;
        this.configurationSha256 = configurationSha256;
        this.createdAt = createdAt;
    }

    public static CertificateProvenance capture(
            String inputIdentifier,
            byte[] input,
            String configuration) throws IOException {
        Objects.requireNonNull(input, "input");
        requireText(inputIdentifier, "input identifier");
        requireText(configuration, "configuration");
        boolean testOnly = Boolean.getBoolean(TEST_OVERRIDE);
        Path root = repositoryRoot();
        String commit = git(root, true, "rev-parse", "HEAD");
        String status = git(root, false, "status", "--porcelain", "--untracked-files=all");
        boolean dirty = !status.isEmpty();
        if (dirty && !testOnly) {
            throw new IOException(
                    "Publication certificate export requires a clean Git worktree");
        }

        Path producerJar = configuredFile(
                "acgn.provenance.producerJar", testOnly, root.resolve("build/missing-producer.jar"));
        Path verifierJar = configuredFile(
                "acgn.provenance.verifierJar", testOnly,
                root.resolve("certificate-verifier/build/missing-verifier.jar"));
        String createdAt = System.getProperty("acgn.provenance.createdAt", "");
        if (createdAt.isBlank()) {
            if (!testOnly) {
                throw new IOException(
                        "Publication certificate export requires acgn.provenance.createdAt");
            }
            createdAt = "1970-01-01T00:00:00Z";
        }

        String sourceHash = fingerprint(root.resolve("src"), ".java");
        String exporterHash = sha256(root.resolve(
                "src/is/fivefivefive/CanDis/theory/CertificateBundleWriter.java"));
        String verifierSourceHash = fingerprint(
                root.resolve("certificate-verifier/src"), ".java");
        String producerJarHash = fileOrTestHash(producerJar, testOnly, "producer-classes");
        String verifierJarHash = fileOrTestHash(verifierJar, testOnly, "verifier-classes");
        String dependencies = dependencyHashes(root.resolve("lib"));
        String canonicalConfiguration = configuration.trim();
        return new CertificateProvenance(
                commit,
                dirty,
                testOnly ? "TEST_ONLY" : "PUBLICATION",
                sourceHash,
                producerJarHash,
                dependencies,
                inputIdentifier,
                sha256(input),
                exporterHash,
                verifierSourceHash,
                verifierJarHash,
                canonicalConfiguration,
                sha256(canonicalConfiguration.getBytes(StandardCharsets.UTF_8)),
                createdAt);
    }

    public void requirePublishable() throws IOException {
        if (dirty && !"TEST_ONLY".equals(mode)) {
            throw new IOException("Dirty provenance cannot be published");
        }
        if (!mode.equals("PUBLICATION") && !mode.equals("TEST_ONLY")) {
            throw new IOException("Unknown provenance mode " + mode);
        }
    }

    public List<String> metadataScalars(String componentVersions, String runId) {
        return List.of(
                commit,
                Boolean.toString(dirty),
                EXPORTER_VERSION,
                componentVersions,
                runId,
                createdAt,
                mode,
                javaSourceSha256,
                producerJarSha256,
                dependencyHashes,
                inputIdentifier,
                inputSha256,
                exporterSourceSha256,
                VERIFIER_VERSION,
                verifierSourceSha256,
                verifierJarSha256,
                configuration,
                configurationSha256);
    }

    public List<String> identityScalars() {
        return List.of(
                commit,
                Boolean.toString(dirty),
                mode,
                javaSourceSha256,
                producerJarSha256,
                dependencyHashes,
                inputIdentifier,
                inputSha256,
                exporterSourceSha256,
                verifierSourceSha256,
                verifierJarSha256,
                configuration,
                configurationSha256,
                createdAt);
    }

    public boolean testOnly() {
        return mode.equals("TEST_ONLY");
    }

    public String inputIdentifier() {
        return inputIdentifier;
    }

    public String inputSha256() {
        return inputSha256;
    }

    public String verifierJarSha256() {
        return verifierJarSha256;
    }

    private static Path repositoryRoot() throws IOException {
        String configured = System.getProperty("acgn.repo.root", "");
        Path cursor = configured.isBlank()
                ? Path.of("").toAbsolutePath().normalize()
                : Path.of(configured).toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(".git"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IOException("Cannot locate the Git repository for certificate provenance");
        }
        return cursor;
    }

    private static String git(
            Path root,
            boolean requireOutput,
            String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .trim();
            if (process.waitFor() != 0) {
                throw new IOException("Git provenance command failed: " + output);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Git provenance command was interrupted", exception);
        }
        if (requireOutput) {
            requireText(output, "Git provenance");
        }
        return output;
    }

    private static Path configuredFile(
            String property,
            boolean testOnly,
            Path fallback) throws IOException {
        String configured = System.getProperty(property, "");
        if (configured.isBlank()) {
            if (!testOnly) {
                throw new IOException("Publication certificate export requires " + property);
            }
            return fallback;
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException(property + " is not a regular file: " + path);
        }
        return path;
    }

    private static String fileOrTestHash(
            Path path,
            boolean testOnly,
            String testLabel) throws IOException {
        if (Files.isRegularFile(path)) {
            return sha256(path);
        }
        if (!testOnly) {
            throw new IOException("Missing publication build artifact " + path);
        }
        return sha256(("TEST_ONLY:" + testLabel).getBytes(StandardCharsets.UTF_8));
    }

    private static String dependencyHashes(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Dependency directory is missing: " + directory);
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IOException("No dependency JARs found in " + directory);
        }
        List<String> entries = new ArrayList<>();
        for (Path file : files) {
            entries.add(directory.relativize(file).toString().replace('\\', '/')
                    + "=" + sha256(file));
        }
        return String.join(";", entries);
    }

    private static String fingerprint(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Fingerprint root is missing: " + root);
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
        MessageDigest digest = digest();
        for (Path file : files) {
            digest.update(root.relativize(file).toString().replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            update(digest, file);
            digest.update((byte) 0xff);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        update(digest, path);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static void update(MessageDigest digest, Path path) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireText(String value, String label) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(label + " must be nonempty");
        }
    }
}
