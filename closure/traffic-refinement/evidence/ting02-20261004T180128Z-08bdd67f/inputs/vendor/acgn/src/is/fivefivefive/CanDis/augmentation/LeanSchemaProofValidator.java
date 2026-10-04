package is.fivefivefive.CanDis.augmentation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Checks a self-contained schema theorem with the installed independent Lean binary. */
public final class LeanSchemaProofValidator {
    public static final String VERSION =
            "adaptive-lean-schema-proof-v5-kernel-bound-schema-digest";
    public static final String TRUSTED_TOOLCHAIN_SHA256 =
            "82d3a147708fde183d0180435a10ea4188975689701e8560946953c4a56d5382";
    public static final String TRUSTED_VERSION =
            "Lean (version 4.33.0, x86_64-unknown-linux-gnu, "
                    + "commit d8b18978322de05a8f3dba51ef03cf5461676c17, Release)";
    private static final int MAX_SOURCE_BYTES = 1_000_000;
    private static final int MAX_OUTPUT_BYTES = 64_000;
    private static final Set<String> ALLOWED_FOUNDATIONAL_AXIOMS = Set.of(
            "propext", "Classical.choice", "Quot.sound");
    private static final List<String> TOOLCHAIN_ROOT_FILES = List.of(
            "bin/lean",
            "lib/lean/libInit_shared.so",
            "lib/lean/libleanshared.so",
            "lib/lean/libleanshared_1.so",
            "lib/lean/libleanshared_2.so");
    private static final Map<String, ToolIdentity> CONFIGURED_TOOL_CACHE =
            new ConcurrentHashMap<>();

    private record ToolIdentity(
            Path executable,
            String toolchainSha256,
            String version) {
        private ToolIdentity {
            executable = executable.toAbsolutePath().normalize();
            toolchainSha256 = requireDigest(
                    toolchainSha256, "toolchainSha256");
            version = requireText(version, "version");
        }
    }

    private final ToolIdentity toolIdentity;

    public LeanSchemaProofValidator() {
        toolIdentity = captureConfiguredTool();
    }

    public String pinnedExecutablePath() {
        return toolIdentity.executable().toString();
    }

    public String pinnedToolchainSha256() {
        return toolIdentity.toolchainSha256();
    }

    public String pinnedVersion() {
        return toolIdentity.version();
    }

    public record Request(
            Path source,
            String theoremName,
            String theoremParameters,
            String theoremStatement,
            String schemaDigest,
            List<String> dependencies,
            Duration timeout) {
        public Request {
            source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
            theoremName = requireIdentifier(theoremName, "theoremName");
            theoremParameters = theoremParameters == null
                    ? "" : theoremParameters.trim();
            if (theoremParameters.contains(":=")
                    || theoremParameters.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(
                        "Lean theorem parameters must be a single declaration prefix");
            }
            theoremStatement = requireText(theoremStatement, "theoremStatement");
            schemaDigest = requireDigest(schemaDigest, "schemaDigest");
            dependencies = List.copyOf(dependencies);
            for (String dependency : dependencies) {
                requireText(dependency, "dependency");
            }
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException(
                        "Lean timeout must be in (0,5 minutes]");
            }
        }
    }

    /** Successful evidence can only be produced by this validator. */
    public static final class Evidence {
        private final String sourcePath;
        private final String sourceSha256;
        private final String schemaDigest;
        private final String theoremName;
        private final String theoremStatementSha256;
        private final List<String> dependencies;
        private final String executablePath;
        private final String toolchainSha256;
        private final String leanVersion;
        private final long elapsedNanos;
        private final String digest;

        private Evidence(
                Path source,
                String sourceSha256,
                String schemaDigest,
                String theoremName,
                String theoremStatementSha256,
                List<String> dependencies,
                String executablePath,
                String toolchainSha256,
                String leanVersion,
                long elapsedNanos) {
            this.sourcePath = source.toString();
            this.sourceSha256 = requireDigest(sourceSha256, "sourceSha256");
            this.schemaDigest = requireDigest(schemaDigest, "schemaDigest");
            this.theoremName = requireIdentifier(theoremName, "theoremName");
            this.theoremStatementSha256 = requireDigest(
                    theoremStatementSha256, "theoremStatementSha256");
            this.dependencies = List.copyOf(dependencies);
            this.executablePath = requireText(executablePath, "executablePath");
            this.toolchainSha256 = requireDigest(
                    toolchainSha256, "toolchainSha256");
            this.leanVersion = requireText(leanVersion, "leanVersion");
            this.elapsedNanos = elapsedNanos;
            this.digest = AugmentationDigests.sha256(String.join("\n",
                    VERSION,
                    sourcePath,
                    sourceSha256,
                    schemaDigest,
                    theoremName,
                    theoremStatementSha256,
                    String.join("\n", this.dependencies),
                    executablePath,
                    toolchainSha256,
                    leanVersion));
        }

        public String sourcePath() {
            return sourcePath;
        }

        public String sourceSha256() {
            return sourceSha256;
        }

        public String schemaDigest() {
            return schemaDigest;
        }

        public String theoremName() {
            return theoremName;
        }

        public String theoremStatementSha256() {
            return theoremStatementSha256;
        }

        public List<String> dependencies() {
            return dependencies;
        }

        public String leanVersion() {
            return leanVersion;
        }

        public String executablePath() {
            return executablePath;
        }

        public String toolchainSha256() {
            return toolchainSha256;
        }

        public long elapsedNanos() {
            return elapsedNanos;
        }

        public String digest() {
            return digest;
        }
    }

    public Evidence verify(Request request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (!Files.isRegularFile(request.source())) {
            throw new IOException("Lean proof source is not a regular file: " + request.source());
        }
        byte[] bytes = Files.readAllBytes(request.source());
        if (bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES) {
            throw new IOException(
                    "Lean proof source size is outside the admitted bound");
        }
        String source = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        String code = stripCommentsAndStrings(source);
        rejectForbiddenConstructs(code);
        requireCorrespondenceMarker(source, request.schemaDigest());
        requireExactTheoremHeader(
                code,
                request.theoremName(),
                request.theoremParameters(),
                request.theoremStatement());

        ToolIdentity currentTool = captureTool(toolIdentity.executable().toString());
        if (!currentTool.equals(toolIdentity)) {
            throw new IOException("Pinned Lean toolchain changed after validator creation");
        }
        long started = System.nanoTime();
        Path proofDirectory = Files.createTempDirectory("acgn-lean-schema-proof-");
        try {
            Path compiled = proofDirectory.resolve("AdaptiveSchemaProof.olean");
            CommandResult result = run(
                    List.of(
                            toolIdentity.executable().toString(),
                            "--stdin",
                            "-DwarningAsError=true",
                            "-o",
                            compiled.toString()),
                    request.timeout(),
                    bytes);
            if (result.exitCode != 0) {
                throw new IOException(
                        "Lean rejected schema proof " + request.source() + ": "
                                + result.output.trim());
            }
            if (!Files.isRegularFile(compiled)) {
                throw new IOException("Lean produced no schema proof module");
            }

            String auditSource = "import AdaptiveSchemaProof\n"
                    + "set_option autoImplicit false in\n"
                    + "example : acgnSchemaDigest = \"" + request.schemaDigest()
                    + "\" := by rfl\n"
                    + "#print axioms " + request.theoremName() + "\n";
            CommandResult audit = run(
                    List.of(
                            toolIdentity.executable().toString(),
                            "--stdin",
                            "-DwarningAsError=true"),
                    request.timeout(),
                    auditSource.getBytes(StandardCharsets.UTF_8),
                    Map.of("LEAN_PATH", proofDirectory.toString()));
            if (audit.exitCode != 0) {
                throw new IOException(
                        "Lean could not audit schema theorem axioms: "
                                + audit.output.trim());
            }
            requireAllowedAxioms(request.theoremName(), audit.output);

            long elapsed = System.nanoTime() - started;
            return new Evidence(
                    request.source(),
                    AugmentationDigests.sha256(bytes),
                    request.schemaDigest(),
                    request.theoremName(),
                    AugmentationDigests.sha256(normalizeWhitespace(
                            request.theoremParameters() + " : "
                                    + request.theoremStatement())),
                    request.dependencies(),
                    toolIdentity.executable().toString(),
                    toolIdentity.toolchainSha256(),
                    toolIdentity.version(),
                    elapsed);
        } finally {
            deleteTree(proofDirectory);
        }
    }

    private static void rejectForbiddenConstructs(String code) {
        String normalized = code.toLowerCase(Locale.ROOT);
        for (String token : List.of(
                "sorry", "sorryax", "axiom", "unsafe", "admit", "opaque",
                "implemented_by")) {
            if (containsToken(normalized, token)) {
                throw new IllegalArgumentException(
                        "Lean schema proof contains forbidden token " + token);
            }
        }
        if (containsToken(normalized, "import")) {
            throw new IllegalArgumentException(
                    "Adaptive schema proofs must be self-contained and cannot import "
                            + "the producer or another discovered schema");
        }
        for (String token : List.of(
                "syntax", "macro", "elab", "parser", "notation", "infix",
                "infixl", "infixr", "prefix", "postfix", "scoped",
                "initialize")) {
            if (containsIdentifierContaining(normalized, token)) {
                throw new IllegalArgumentException(
                        "Adaptive schema proofs cannot extend Lean's checked language "
                                + "through " + token);
            }
        }
    }

    private static void requireAllowedAxioms(
            String theoremName,
            String output) throws IOException {
        String normalized = normalizeWhitespace(output);
        String prefix = "'" + theoremName + "' ";
        if (normalized.equals(prefix + "does not depend on any axioms")) {
            return;
        }
        String dependentPrefix = prefix + "depends on axioms: [";
        if (!normalized.startsWith(dependentPrefix) || !normalized.endsWith("]")) {
            throw new IOException(
                    "Lean returned an unrecognized axiom audit: " + normalized);
        }
        String body = normalized.substring(
                dependentPrefix.length(), normalized.length() - 1).trim();
        Set<String> dependencies = new LinkedHashSet<>();
        if (!body.isEmpty()) {
            for (String dependency : body.split(",")) {
                dependencies.add(dependency.trim());
            }
        }
        if (!ALLOWED_FOUNDATIONAL_AXIOMS.containsAll(dependencies)) {
            Set<String> rejected = new LinkedHashSet<>(dependencies);
            rejected.removeAll(ALLOWED_FOUNDATIONAL_AXIOMS);
            throw new IOException(
                    "Lean schema theorem depends on unapproved axioms: " + rejected);
        }
    }

    private static void requireCorrespondenceMarker(String source, String digest) {
        String expected = "def acgnSchemaDigest : String := \"" + digest + "\"";
        String executable = stripComments(source, true);
        if (!normalizeWhitespace(executable).contains(normalizeWhitespace(expected))) {
            throw new IllegalArgumentException(
                    "Lean proof does not bind the exact inferred schema digest");
        }
    }

    private static void requireExactTheoremHeader(
            String code,
            String theoremName,
            String theoremParameters,
            String theoremStatement) {
        String normalized = normalizeWhitespace(code);
        String expected = normalizeWhitespace(
                "theorem " + theoremName + " " + theoremParameters
                        + " : " + theoremStatement + " :=");
        if (!normalized.contains(expected)) {
            throw new IllegalArgumentException(
                    "Lean proof theorem header does not match the admitted schema statement");
        }
    }

    private static boolean containsToken(String source, String token) {
        int index = -1;
        while ((index = source.indexOf(token, index + 1)) >= 0) {
            boolean left = index == 0 || !isIdentifierPart(source.charAt(index - 1));
            int end = index + token.length();
            boolean right = end == source.length()
                    || !isIdentifierPart(source.charAt(end));
            if (left && right) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIdentifierContaining(
            String source,
            String fragment) {
        for (int start = 0; start < source.length();) {
            if (!isIdentifierPart(source.charAt(start))) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < source.length() && isIdentifierPart(source.charAt(end))) {
                end++;
            }
            if (source.substring(start, end).contains(fragment)) {
                return true;
            }
            start = end;
        }
        return false;
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '\'';
    }

    private static String stripCommentsAndStrings(String source) {
        return stripComments(source, false);
    }

    private static String stripComments(
            String source,
            boolean preserveStrings) {
        StringBuilder output = new StringBuilder(source.length());
        int blockDepth = 0;
        boolean lineComment = false;
        boolean string = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    output.append('\n');
                } else {
                    output.append(' ');
                }
                continue;
            }
            if (blockDepth > 0) {
                if (current == '/' && next == '-') {
                    blockDepth++;
                    output.append("  ");
                    index++;
                } else if (current == '-' && next == '/') {
                    blockDepth--;
                    output.append("  ");
                    index++;
                } else {
                    output.append(current == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (string) {
                output.append(preserveStrings
                        ? current : current == '\n' ? '\n' : ' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    string = false;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                output.append("  ");
                index++;
            } else if (current == '/' && next == '-') {
                blockDepth = 1;
                output.append("  ");
                index++;
            } else if (current == '"') {
                string = true;
                output.append(preserveStrings ? current : ' ');
            } else {
                output.append(current);
            }
        }
        if (blockDepth != 0 || string) {
            throw new IllegalArgumentException(
                    "Lean proof contains an unterminated comment or string");
        }
        return output.toString();
    }

    private static ToolIdentity captureConfiguredTool() {
        String launcher = System.getProperty("acgn.lean", "lean");
        ToolIdentity cached = CONFIGURED_TOOL_CACHE.get(launcher);
        if (cached != null) {
            return cached;
        }
        try {
            ToolIdentity captured = captureTool(launcher);
            ToolIdentity raced = CONFIGURED_TOOL_CACHE.putIfAbsent(
                    launcher, captured);
            return raced == null ? captured : raced;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "The independently pinned Lean toolchain is unavailable",
                    exception);
        }
    }

    private static ToolIdentity captureTool(String launcher) throws IOException {
        String checkedLauncher = requireText(launcher, "Lean launcher");
        CommandResult prefixResult = run(
                List.of(checkedLauncher, "--print-prefix"), Duration.ofSeconds(15));
        if (prefixResult.exitCode != 0) {
            throw new IOException(
                    "Lean launcher did not resolve a toolchain prefix: "
                            + prefixResult.output.trim());
        }
        String prefixText = prefixResult.output.trim();
        if (prefixText.isEmpty() || prefixText.indexOf('\n') >= 0) {
            throw new IOException("Lean launcher returned an invalid toolchain prefix");
        }
        Path prefix = Path.of(prefixText).toAbsolutePath().normalize();
        Path executable = prefix.resolve("bin/lean").normalize();
        Set<Path> files = new LinkedHashSet<>();
        for (String relative : TOOLCHAIN_ROOT_FILES) {
            files.add(prefix.resolve(relative).normalize());
        }
        Path leanLibrary = prefix.resolve("lib/lean").normalize();
        try (var roots = Files.list(leanLibrary)) {
            roots.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().startsWith("Init."))
                    .forEach(files::add);
        }
        Path initLibrary = leanLibrary.resolve("Init");
        try (var initFiles = Files.walk(initLibrary)) {
            initFiles.filter(Files::isRegularFile).forEach(files::add);
        }
        List<String> identities = new ArrayList<>();
        for (Path file : files.stream().sorted(Comparator.comparing(path ->
                        prefix.relativize(path).toString().replace('\\', '/'))).toList()) {
            String relative = prefix.relativize(file).toString().replace('\\', '/');
            if (!file.startsWith(prefix) || !Files.isRegularFile(file)) {
                throw new IOException(
                        "Pinned Lean toolchain component is missing: " + relative);
            }
            identities.add(relative + "=" + sha256File(file));
        }
        String toolchainDigest = AugmentationDigests.sha256(
                String.join("\n", identities) + "\n");
        if (!TRUSTED_TOOLCHAIN_SHA256.equals(toolchainDigest)) {
            throw new IOException(
                    "Lean toolchain digest is not the artifact-authorized pin: "
                            + toolchainDigest);
        }
        CommandResult versionResult = run(
                List.of(executable.toString(), "--version"), Duration.ofSeconds(15));
        String version = versionResult.output.trim();
        if (versionResult.exitCode != 0 || !TRUSTED_VERSION.equals(version)) {
            throw new IOException(
                    "Lean version does not match the artifact-authorized pin: " + version);
        }
        return new ToolIdentity(executable, toolchainDigest, version);
    }

    private static String sha256File(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CommandResult run(List<String> command, Duration timeout)
            throws IOException {
        return run(command, timeout, null);
    }

    private static CommandResult run(
            List<String> command,
            Duration timeout,
            byte[] standardInput) throws IOException {
        return run(command, timeout, standardInput, Map.of());
    }

    private static CommandResult run(
            List<String> command,
            Duration timeout,
            byte[] standardInput,
            Map<String, String> environment) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command))
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        if (standardInput != null) {
            try (var input = process.getOutputStream()) {
                input.write(standardInput);
            } catch (IOException exception) {
                process.destroyForcibly();
                throw new IOException(
                        "Cannot provide the checked Lean proof bytes", exception);
            }
        }
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Lean validation was interrupted", exception);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Lean validation exceeded " + timeout);
        }
        byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
        if (output.length > MAX_OUTPUT_BYTES) {
            throw new IOException("Lean output exceeded the admitted bound");
        }
        return new CommandResult(
                process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    private static void deleteTree(Path root) throws IOException {
        IOException failure = null;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record CommandResult(int exitCode, String output) {
    }

    private static String normalizeWhitespace(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String requireIdentifier(String value, String label) {
        String checked = requireText(value, label);
        if (!checked.matches("[A-Za-z_][A-Za-z0-9_']*")) {
            throw new IllegalArgumentException(label + " is not a Lean identifier");
        }
        return checked;
    }

    private static String requireDigest(String value, String label) {
        String checked = requireText(value, label);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is not SHA-256");
        }
        return checked;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
