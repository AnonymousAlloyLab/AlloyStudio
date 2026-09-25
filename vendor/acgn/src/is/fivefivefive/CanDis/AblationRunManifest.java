package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

import is.fivefivefive.CanDis.core.egraph.JavaEgglog;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.theory.BoundedFiniteUnfoldingOracle;
import is.fivefivefive.CanDis.theory.CertificateVerifier;
import is.fivefivefive.CanDis.theory.ProductionGraphCanonicalizer;

/** Creates and validates reproducibility manifests for process-isolated ablation runs. */
final class AblationRunManifest {
    static final String MANIFEST_SCHEMA_VERSION = "candis-ablation-manifest-v3";
    static final String OUTPUT_SCHEMA_VERSION = "candis-ablation-output-v5";
    static final String ARM_MANIFEST = "manifest.json";
    static final String ROOT_MANIFEST = "run-manifest.json";
    private static final List<String> ARM_OUTPUTS = List.of(
            "pairs.csv", "summary.json", "metrics.properties", "run.log", "process.time");

    private AblationRunManifest() {
    }

    static Context capture(
            Path input,
            String heap,
            int workers,
            int limit,
            long seed) throws IOException {
        Fingerprint dataset = fingerprint(input, ".als");
        Fingerprint sources = fingerprint(Paths.get("src"), ".java");
        String gitSha = command("git", "rev-parse", "HEAD");
        String gitStatus = command("git", "status", "--porcelain", "--untracked-files=normal");
        return new Context(
                UUID.randomUUID().toString(),
                gitSha.isEmpty() ? "unknown" : gitSha,
                gitSha.isEmpty() || !gitStatus.isEmpty(),
                sources.sha256,
                input.toAbsolutePath().normalize().toString(),
                dataset.sha256,
                dataset.fileCount,
                dataset.byteCount,
                JavaEgglog.ruleSetVersion(),
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.vendor", "unknown"),
                heap,
                workers,
                AblationParallelism.logicalProcessors(),
                hostName(),
                cpuModel(),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"),
                Instant.now().toString(),
                limit,
                seed);
    }

    static void writeArm(Path engineDir, String engine, Context context) throws IOException {
        JSONObject manifest = context.toJson();
        manifest.put("arm", engine);
        manifest.put("engine", engineMetadata(engine));
        Properties metrics = new Properties();
        try (InputStream input = Files.newInputStream(engineDir.resolve("metrics.properties"))) {
            metrics.load(input);
        }
        manifest.put("result", new JSONObject()
                .put("files", longProperty(metrics, "files"))
                .put("successes", longProperty(metrics, "successes"))
                .put("skippedIdenticalRawAstPairs", longProperty(metrics, "skippedIdenticalRawAstPairs"))
                .put("failures", longProperty(metrics, "failures")));
        manifest.put("outputs", outputEntries(engineDir, ARM_OUTPUTS));
        Files.writeString(engineDir.resolve(ARM_MANIFEST), manifest.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    private static JSONObject engineMetadata(String engine) {
        boolean exact = "typed-slotted-port-egraph".equals(engine);
        JSONObject bounded = new JSONObject();
        if (exact) {
            bounded.put("legacyRewriteIterations", 0)
                    .put("legacyMaximumTermSize", 0)
                    .put("finiteUnfoldingMaximumDepth", "eclasses+1")
                    .put("finiteUnfoldingMaximumAlternatives", 64);
        } else {
            bounded.put("legacyRewriteIterations", 32)
                    .put("legacyMaximumTermSize", 50_000)
                    .put("finiteUnfoldingMaximumDepth", "not-applicable")
                    .put("finiteUnfoldingMaximumAlternatives", 0);
        }
        return new JSONObject()
                .put("armId", engine)
                .put("engineIdentifier", exact
                        ? "TypedSlottedPortEGraph"
                        : "legacy-bounded/" + engine)
                .put("theoryFaithfulEngineUsed", exact)
                .put("invariantCheckMode", exact
                        ? TheoryAlloyAdapter.INVARIANT_MODE
                        : "legacy-arm-local-checks")
                .put("ruleSetId", exact
                        ? TheoryAlloyAdapter.SIGNATURE_VERSION
                        : JavaEgglog.ruleSetVersion())
                .put("canonicalizerVersion", exact
                        ? ProductionGraphCanonicalizer.VERSION
                        : "legacy-bounded")
                .put("certificateMode", exact ? "required" : "not-applicable")
                .put("certificateVerifierVersion", exact
                        ? CertificateVerifier.VERSION : "not-applicable")
                .put("finiteUnfoldingVersion", exact
                        ? BoundedFiniteUnfoldingOracle.VERSION : "not-applicable")
                .put("alloyAdapterVersion", exact
                        ? TheoryAlloyAdapter.ADAPTER_VERSION : "not-applicable")
                .put("canonicalPipelineVersion", exact
                        ? CanonicalAlloyPipeline.PIPELINE_VERSION : "legacy-bounded")
                .put("measurementProjectionVersion", exact
                        ? CanonicalAlloyPipeline.MEASUREMENT_PROJECTION_VERSION
                        : "not-applicable")
                .put("quotientMetricVersion", exact
                        ? CanonicalAlloyPipeline.QUOTIENT_METRIC_VERSION
                        : "not-applicable")
                .put("canonicalRepresentativeTedVersion", exact
                        ? CanonicalAlloyPipeline.REPRESENTATIVE_TED_VERSION
                        : "not-applicable")
                .put("boundedSettings", bounded);
    }

    static Context loadCompatibleArms(Path output, List<String> engines) throws IOException {
        Context expected = null;
        for (String engine : engines) {
            Path engineDir = output.resolve(engine);
            Path manifestPath = engineDir.resolve(ARM_MANIFEST);
            if (!Files.isRegularFile(manifestPath)) {
                throw new IllegalStateException("Missing arm manifest: " + manifestPath);
            }
            JSONObject manifest = new JSONObject(Files.readString(manifestPath, StandardCharsets.UTF_8));
            if (!engine.equals(manifest.optString("arm"))) {
                throw new IllegalStateException(manifestPath + " belongs to arm " + manifest.optString("arm"));
            }
            Context context = Context.fromJson(manifest);
            if (expected == null) {
                expected = context;
            } else {
                expected.requireCompatible(context, engine);
            }
            verifyOutputs(engineDir, manifest.getJSONArray("outputs"), manifestPath);
        }
        if (expected == null) {
            throw new IllegalStateException("No ablation arm manifests found");
        }
        return expected;
    }

    static void writeRoot(Path output, Context context, List<String> generatedOutputs) throws IOException {
        JSONObject manifest = context.toJson();
        manifest.put("completedAt", Instant.now().toString());
        manifest.put("outputs", outputEntries(output, generatedOutputs));
        Files.writeString(output.resolve(ROOT_MANIFEST), manifest.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    static List<String> allGeneratedOutputs(List<String> engines) {
        List<String> outputs = new ArrayList<>();
        for (String engine : engines) {
            for (String output : ARM_OUTPUTS) {
                outputs.add(engine + "/" + output);
            }
            outputs.add(engine + "/" + ARM_MANIFEST);
        }
        outputs.add("comparison.json");
        outputs.add("minimum_distances.csv");
        outputs.add("equivalence_disagreements.csv");
        outputs.add("canonical_only_vs_slotted.md");
        outputs.add("summary.md");
        return outputs;
    }

    private static JSONArray outputEntries(Path base, List<String> outputs) throws IOException {
        JSONArray entries = new JSONArray();
        Path normalizedBase = base.toAbsolutePath().normalize();
        for (String relative : outputs) {
            Path path = normalizedBase.resolve(relative).normalize();
            if (!path.startsWith(normalizedBase)) {
                throw new IllegalArgumentException("Output escapes manifest root: " + relative);
            }
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Missing generated output: " + path);
            }
            entries.put(new JSONObject()
                    .put("path", relative.replace('\\', '/'))
                    .put("sizeBytes", Files.size(path))
                    .put("sha256", sha256(path)));
        }
        return entries;
    }

    private static void verifyOutputs(Path base, JSONArray outputs, Path manifestPath) throws IOException {
        Path normalizedBase = base.toAbsolutePath().normalize();
        for (int i = 0; i < outputs.length(); i++) {
            JSONObject output = outputs.getJSONObject(i);
            Path path = normalizedBase.resolve(output.getString("path")).normalize();
            if (!path.startsWith(normalizedBase) || !Files.isRegularFile(path)) {
                throw new IllegalStateException(manifestPath + " references missing output " + path);
            }
            String actual = sha256(path);
            if (!actual.equals(output.getString("sha256")) || Files.size(path) != output.getLong("sizeBytes")) {
                throw new IllegalStateException("Output hash mismatch for " + path + " against " + manifestPath);
            }
        }
    }

    private static Fingerprint fingerprint(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Fingerprint root is not a directory: " + root);
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .collect(Collectors.toList());
        }
        MessageDigest digest = newDigest();
        long bytes = 0;
        byte[] buffer = new byte[64 * 1024];
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                        bytes += read;
                    }
                }
            }
            digest.update((byte) 0xff);
        }
        return new Fingerprint(files.size(), bytes, hex(digest.digest()));
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String command(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? output : "";
        } catch (IOException exception) {
            return "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static String hostName() {
        try {
            String resolved = InetAddress.getLocalHost().getHostName();
            if (!resolved.isBlank() && !"unknown".equalsIgnoreCase(resolved)) {
                return resolved;
            }
        } catch (IOException exception) {
            // Fall through to sources that do not require DNS or /etc/hosts.
        }
        String environment = System.getenv("HOSTNAME");
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }
        Path hostname = Paths.get("/etc/hostname");
        try {
            String configured = Files.readString(hostname, StandardCharsets.UTF_8).trim();
            return configured.isEmpty() ? "unknown" : configured;
        } catch (IOException exception) {
            return "unknown";
        }
    }

    private static String cpuModel() {
        Path cpuInfo = Paths.get("/proc/cpuinfo");
        if (Files.isRegularFile(cpuInfo)) {
            try {
                for (String line : Files.readAllLines(cpuInfo, StandardCharsets.UTF_8)) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("model name")) {
                        int colon = line.indexOf(':');
                        return colon < 0 ? line.trim() : line.substring(colon + 1).trim();
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return System.getProperty("os.arch", "unknown");
    }

    private static long longProperty(Properties properties, String key) {
        return Long.parseLong(properties.getProperty(key, "0"));
    }

    static final class Context {
        final String runId;
        final String gitSha;
        final boolean dirtyTree;
        final String javaSourceSha256;
        final String datasetRoot;
        final String datasetSha256;
        final long datasetFileCount;
        final long datasetByteCount;
        final String ruleSetVersion;
        final String javaVersion;
        final String javaVendor;
        final String heap;
        final int workers;
        final int logicalProcessors;
        final String host;
        final String cpu;
        final String osName;
        final String osVersion;
        final String osArch;
        final String startedAt;
        final int limit;
        final long seed;

        private Context(
                String runId,
                String gitSha,
                boolean dirtyTree,
                String javaSourceSha256,
                String datasetRoot,
                String datasetSha256,
                long datasetFileCount,
                long datasetByteCount,
                String ruleSetVersion,
                String javaVersion,
                String javaVendor,
                String heap,
                int workers,
                int logicalProcessors,
                String host,
                String cpu,
                String osName,
                String osVersion,
                String osArch,
                String startedAt,
                int limit,
                long seed) {
            this.runId = runId;
            this.gitSha = gitSha;
            this.dirtyTree = dirtyTree;
            this.javaSourceSha256 = javaSourceSha256;
            this.datasetRoot = datasetRoot;
            this.datasetSha256 = datasetSha256;
            this.datasetFileCount = datasetFileCount;
            this.datasetByteCount = datasetByteCount;
            this.ruleSetVersion = ruleSetVersion;
            this.javaVersion = javaVersion;
            this.javaVendor = javaVendor;
            this.heap = heap;
            this.workers = workers;
            this.logicalProcessors = logicalProcessors;
            this.host = host;
            this.cpu = cpu;
            this.osName = osName;
            this.osVersion = osVersion;
            this.osArch = osArch;
            this.startedAt = startedAt;
            this.limit = limit;
            this.seed = seed;
        }

        JSONObject toJson() {
            return new JSONObject()
                    .put("manifestSchemaVersion", MANIFEST_SCHEMA_VERSION)
                    .put("outputSchemaVersion", OUTPUT_SCHEMA_VERSION)
                    .put("runId", runId)
                    .put("gitSha", gitSha)
                    .put("dirtyTree", dirtyTree)
                    .put("javaSourceSha256", javaSourceSha256)
                    .put("datasetRoot", datasetRoot)
                    .put("datasetSha256", datasetSha256)
                    .put("datasetFileCount", datasetFileCount)
                    .put("datasetByteCount", datasetByteCount)
                    .put("ruleSetVersion", ruleSetVersion)
                    .put("ruleNames", new JSONArray(JavaEgglog.ruleNames()))
                    .put("javaVersion", javaVersion)
                    .put("javaVendor", javaVendor)
                    .put("heap", heap)
                    .put("workers", workers)
                    .put("logicalProcessors", logicalProcessors)
                    .put("host", host)
                    .put("cpu", cpu)
                    .put("osName", osName)
                    .put("osVersion", osVersion)
                    .put("osArch", osArch)
                    .put("startedAt", startedAt)
                    .put("limit", limit)
                    .put("seed", seed);
        }

        static Context fromJson(JSONObject json) {
            if (!MANIFEST_SCHEMA_VERSION.equals(json.optString("manifestSchemaVersion"))) {
                throw new IllegalStateException("Unsupported manifest schema: "
                        + json.optString("manifestSchemaVersion"));
            }
            if (!OUTPUT_SCHEMA_VERSION.equals(json.optString("outputSchemaVersion"))) {
                throw new IllegalStateException("Unsupported output schema: "
                        + json.optString("outputSchemaVersion"));
            }
            return new Context(
                    json.getString("runId"),
                    json.getString("gitSha"),
                    json.getBoolean("dirtyTree"),
                    json.getString("javaSourceSha256"),
                    json.getString("datasetRoot"),
                    json.getString("datasetSha256"),
                    json.getLong("datasetFileCount"),
                    json.getLong("datasetByteCount"),
                    json.getString("ruleSetVersion"),
                    json.getString("javaVersion"),
                    json.getString("javaVendor"),
                    json.getString("heap"),
                    json.getInt("workers"),
                    json.getInt("logicalProcessors"),
                    json.getString("host"),
                    json.getString("cpu"),
                    json.getString("osName"),
                    json.getString("osVersion"),
                    json.getString("osArch"),
                    json.getString("startedAt"),
                    json.getInt("limit"),
                    json.getLong("seed"));
        }

        void requireCompatible(Context other, String arm) {
            require(runId, other.runId, "runId", arm);
            require(gitSha, other.gitSha, "gitSha", arm);
            require(dirtyTree, other.dirtyTree, "dirtyTree", arm);
            require(javaSourceSha256, other.javaSourceSha256, "javaSourceSha256", arm);
            require(datasetRoot, other.datasetRoot, "datasetRoot", arm);
            require(datasetSha256, other.datasetSha256, "datasetSha256", arm);
            require(datasetFileCount, other.datasetFileCount, "datasetFileCount", arm);
            require(datasetByteCount, other.datasetByteCount, "datasetByteCount", arm);
            require(ruleSetVersion, other.ruleSetVersion, "ruleSetVersion", arm);
            require(javaVersion, other.javaVersion, "javaVersion", arm);
            require(javaVendor, other.javaVendor, "javaVendor", arm);
            require(heap, other.heap, "heap", arm);
            require(workers, other.workers, "workers", arm);
            require(logicalProcessors, other.logicalProcessors, "logicalProcessors", arm);
            require(host, other.host, "host", arm);
            require(cpu, other.cpu, "cpu", arm);
            require(osName, other.osName, "osName", arm);
            require(osVersion, other.osVersion, "osVersion", arm);
            require(osArch, other.osArch, "osArch", arm);
            require(startedAt, other.startedAt, "startedAt", arm);
            require(limit, other.limit, "limit", arm);
            require(seed, other.seed, "seed", arm);
        }

        private static void require(Object expected, Object actual, String field, String arm) {
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Incompatible " + arm + " manifest field " + field
                        + ": expected " + expected + " but found " + actual);
            }
        }
    }

    private static final class Fingerprint {
        private final long fileCount;
        private final long byteCount;
        private final String sha256;

        private Fingerprint(long fileCount, long byteCount, String sha256) {
            this.fileCount = fileCount;
            this.byteCount = byteCount;
            this.sha256 = sha256;
        }
    }
}
