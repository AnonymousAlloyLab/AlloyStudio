package is.fivefivefive.CanDis.theory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded client for the standalone verifier. The configured JAR is copied
 * only after its exact digest is checked, so the executed JAR bytes are the
 * bytes the caller selected. A result observed by the producer process is not
 * self-authenticating; independent audit must capture the standalone process
 * output outside the producer trust domain.
 */
public final class IndependentCertificateVerifier {
    public enum Profile {
        FULL,
        PAIR
    }

    public enum Outcome {
        VERIFIED,
        UNCHECKABLE,
        REJECTED
    }

    public record Result(Outcome outcome, String code, String detail, int exitCode) {
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            requireText(code, "code");
            Objects.requireNonNull(detail, "detail");
            int expected = switch (outcome) {
                case VERIFIED -> 0;
                case REJECTED -> 2;
                case UNCHECKABLE -> 3;
            };
            if (exitCode != expected || (outcome == Outcome.VERIFIED) != "NONE".equals(code)) {
                throw new IllegalArgumentException(
                        "Standalone verifier outcome, code, and exit status disagree");
            }
        }

        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    public static final class Policy {
        private final Path verifierJar;
        private final String expectedVerifierSha256;
        private final String trustedTheoryDigest;
        private final Duration timeout;
        private final long maxVerifierBytes;
        private final int maxOutputBytes;
        private final boolean allowTestOnlyEvidence;
        private final java.util.Map<String, String> callOccurrenceCommitments;

        public Policy(
                Path verifierJar,
                String expectedVerifierSha256,
                String trustedTheoryDigest,
                Duration timeout,
                long maxVerifierBytes,
                int maxOutputBytes,
                boolean allowTestOnlyEvidence) {
            this(
                    verifierJar,
                    expectedVerifierSha256,
                    trustedTheoryDigest,
                    timeout,
                    maxVerifierBytes,
                    maxOutputBytes,
                    allowTestOnlyEvidence,
                    java.util.Map.of());
        }

        public Policy(
                Path verifierJar,
                String expectedVerifierSha256,
                String trustedTheoryDigest,
                Duration timeout,
                long maxVerifierBytes,
                int maxOutputBytes,
                boolean allowTestOnlyEvidence,
                java.util.Map<String, String> callOccurrenceCommitments) {
            this.verifierJar = Objects.requireNonNull(
                    verifierJar, "verifierJar").toAbsolutePath().normalize();
            this.expectedVerifierSha256 = requireDigest(
                    expectedVerifierSha256, "expectedVerifierSha256");
            this.trustedTheoryDigest = requireDigest(
                    trustedTheoryDigest, "trustedTheoryDigest");
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Verifier timeout must be positive");
            }
            if (maxVerifierBytes <= 0L || maxOutputBytes <= 0) {
                throw new IllegalArgumentException("Verifier byte bounds must be positive");
            }
            this.maxVerifierBytes = maxVerifierBytes;
            this.maxOutputBytes = maxOutputBytes;
            this.allowTestOnlyEvidence = allowTestOnlyEvidence;
            this.callOccurrenceCommitments = java.util.Map.copyOf(
                    callOccurrenceCommitments);
            for (java.util.Map.Entry<String, String> entry
                    : this.callOccurrenceCommitments.entrySet()) {
                requireDigest(entry.getKey(), "CALL occurrence input hash");
                requireDigest(entry.getValue(), "CALL occurrence digest");
            }
        }

        public Path verifierJar() {
            return verifierJar;
        }

        public String expectedVerifierSha256() {
            return expectedVerifierSha256;
        }

        public String trustedTheoryDigest() {
            return trustedTheoryDigest;
        }

        public Duration timeout() {
            return timeout;
        }

        public long maxVerifierBytes() {
            return maxVerifierBytes;
        }

        public int maxOutputBytes() {
            return maxOutputBytes;
        }

        public boolean allowTestOnlyEvidence() {
            return allowTestOnlyEvidence;
        }

        public java.util.Map<String, String> callOccurrenceCommitments() {
            return callOccurrenceCommitments;
        }
    }

    private final Policy policy;
    private final Path stagedJar;

    public IndependentCertificateVerifier(Policy policy, Path privateDirectory)
            throws IOException {
        this.policy = Objects.requireNonNull(policy, "policy");
        Path directory = Objects.requireNonNull(
                privateDirectory, "privateDirectory").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        this.stagedJar = directory.resolve("verified-standalone-verifier.jar");
        stageVerifier(policy.verifierJar(), stagedJar);
    }

    public Result verify(Profile profile, List<Path> bundles) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(bundles, "bundles");
        int expected = profile == Profile.PAIR ? 2 : 1;
        if (bundles.size() != expected) {
            throw new IllegalArgumentException(
                    profile + " requires exactly " + expected + " bundle(s)");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(stagedJar.toString());
        command.add("--profile");
        command.add(profile.name().toLowerCase(java.util.Locale.ROOT));
        command.add("--theory-digest");
        command.add(policy.trustedTheoryDigest());
        for (java.util.Map.Entry<String, String> commitment
                : policy.callOccurrenceCommitments().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            command.add("--call-occurrence-commitment");
            command.add(commitment.getKey() + "=" + commitment.getValue());
        }
        for (Path bundle : bundles) {
            Path checked = Objects.requireNonNull(bundle, "bundle")
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(checked)) {
                throw new IOException("Certificate bundle is missing: " + checked);
            }
            command.add(checked.toString());
        }
        return run(command);
    }

    private void stageVerifier(Path source, Path destination) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Standalone verifier JAR is missing: " + source);
        }
        long size = Files.size(source);
        if (size <= 0L || size > policy.maxVerifierBytes()) {
            throw new IOException("Standalone verifier JAR exceeds its byte bound");
        }
        byte[] bytes = Files.readAllBytes(source);
        String actual = sha256(bytes);
        if (!policy.expectedVerifierSha256().equals(actual)) {
            throw new IOException("Standalone verifier JAR digest mismatch: " + actual);
        }
        if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to replace a staged verifier path");
        }
        Files.write(destination, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        if (!actual.equals(sha256(Files.readAllBytes(destination)))) {
            throw new IOException("Staged standalone verifier JAR changed during staging");
        }
    }

    private Result run(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        for (String variable : List.of(
                "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        BoundedCollector collector = new BoundedCollector(
                process.getInputStream(), policy.maxOutputBytes(), process);
        Thread reader = new Thread(collector, "acgn-independent-verifier-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(
                    policy.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Standalone verification was interrupted", exception);
        }
        if (!finished) {
            process.destroyForcibly();
            join(reader);
            throw new IOException("Standalone verification exceeded " + policy.timeout());
        }
        join(reader);
        if (collector.failure != null) {
            throw collector.failure;
        }
        String output = collector.text();
        String outcomeText = jsonString(output, "outcome");
        String code = jsonString(output, "code");
        String detail = jsonString(output, "detail");
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(outcomeText);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Standalone verifier returned an unknown outcome", exception);
        }
        try {
            return new Result(outcome, code, detail, process.exitValue());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Standalone verifier returned an incoherent result", exception);
        }
    }

    private static void join(Thread thread) throws IOException {
        try {
            thread.join(10_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Verifier output collection was interrupted", exception);
        }
        if (thread.isAlive()) {
            throw new IOException("Verifier output collector did not terminate");
        }
    }

    private static String jsonString(String json, String name) throws IOException {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IOException("Standalone verifier output lacks " + name);
        }
        start += marker.length();
        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        for (int index = start; index < json.length(); index++) {
            char current = json.charAt(index);
            if (escaping) {
                value.append(switch (current) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case '\\', '"' -> current;
                    default -> throw new IOException(
                            "Standalone verifier output has an invalid escape");
                });
                escaping = false;
            } else if (current == '\\') {
                escaping = true;
            } else if (current == '"') {
                return value.toString();
            } else {
                value.append(current);
            }
        }
        throw new IOException("Standalone verifier output has an unterminated " + name);
    }

    private static String requireDigest(String value, String label) {
        requireText(value, label);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class BoundedCollector implements Runnable {
        private final InputStream input;
        private final int maximum;
        private final Process process;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicBoolean exceeded = new AtomicBoolean();
        private volatile IOException failure;

        private BoundedCollector(InputStream input, int maximum, Process process) {
            this.input = input;
            this.maximum = maximum;
            this.process = process;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            try (InputStream source = input) {
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (output.size() + read > maximum) {
                        exceeded.set(true);
                        process.destroyForcibly();
                        throw new IOException("Standalone verifier output exceeds its byte bound");
                    }
                    output.write(buffer, 0, read);
                }
            } catch (IOException exception) {
                failure = exception;
            }
        }

        private String text() throws IOException {
            if (exceeded.get()) {
                throw new IOException("Standalone verifier output exceeds its byte bound");
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
