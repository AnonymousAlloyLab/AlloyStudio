package is.fivefivefive.CanDis.augmentation;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.theory.AlloyLawRegistry;
import is.fivefivefive.CanDis.theory.CertificateTheoryManifest;
import is.fivefivefive.CanDis.theory.CertificateVerifier;
import parser.ast.nodes.ModelUnit;

/** Immutable identity of the certified theory present before adaptive admission. */
public final class BootstrapTheoryR0 {
    public static final String VERSION = "adaptive-equivalence-bootstrap-r0-v1";

    private final List<String> components;
    private final String digest;

    private BootstrapTheoryR0(List<String> components) {
        this.components = List.copyOf(components);
        this.digest = AugmentationDigests.sha256(String.join("\n", this.components));
    }

    public static BootstrapTheoryR0 current() {
        return new BootstrapTheoryR0(List.of(
                VERSION,
                "producer-runtime-image=" + runtimeImageDigest(
                        CanonicalAlloyPipeline.class),
                "alloy-runtime-image=" + runtimeImageDigest(CompUtil.class),
                "parser-runtime-image=" + runtimeImageDigest(ModelUnit.class),
                "lean-toolchain="
                        + LeanSchemaProofValidator.TRUSTED_TOOLCHAIN_SHA256,
                "lean-version=" + LeanSchemaProofValidator.TRUSTED_VERSION,
                CanonicalAlloyPipeline.PIPELINE_VERSION,
                CertificateTheoryManifest.VERSION,
                CertificateVerifier.VERSION,
                AlloyLawRegistry.VERSION,
                AlloyLawRegistry.SOURCE_THEORY_DIGEST,
                QuotientRepairDistance.VERSION));
    }

    private static String runtimeImageDigest(Class<?> anchor) {
        Objects.requireNonNull(anchor, "anchor");
        try {
            URI location = anchor.getProtectionDomain().getCodeSource()
                    .getLocation().toURI();
            if (!"file".equalsIgnoreCase(location.getScheme())) {
                throw new IllegalStateException(
                        "Unsupported R0 runtime-image location " + location);
            }
            Path path = Path.of(location).toAbsolutePath().normalize();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(path)) {
                update(digest, path.getFileName().toString());
                updateFile(digest, path);
            } else if (Files.isDirectory(path)) {
                List<Path> classes;
                try (var stream = Files.walk(path)) {
                    classes = stream.filter(Files::isRegularFile)
                            .filter(file -> file.getFileName().toString().endsWith(".class"))
                            .sorted(Comparator.comparing(file ->
                                    path.relativize(file).toString().replace('\\', '/')))
                            .toList();
                }
                if (classes.isEmpty()) {
                    throw new IllegalStateException(
                            "R0 runtime image contains no class files: " + path);
                }
                for (Path file : classes) {
                    update(digest, path.relativize(file).toString().replace('\\', '/'));
                    updateFile(digest, file);
                }
            } else {
                throw new IllegalStateException(
                        "R0 runtime image does not exist: " + path);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.net.URISyntaxException
                | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Cannot bind immutable R0 to its runtime image", exception);
        }
    }

    private static void updateFile(MessageDigest digest, Path file) throws IOException {
        long size = Files.size(file);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(size).array());
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public List<String> components() {
        return components;
    }

    public String digest() {
        return digest;
    }

    public void requireSame(BootstrapTheoryR0 other) {
        if (!equals(Objects.requireNonNull(other, "other"))) {
            throw new IllegalArgumentException(
                    "Adaptive theories use different immutable R0 bootstraps");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BootstrapTheoryR0
                && components.equals(((BootstrapTheoryR0) other).components)
                && digest.equals(((BootstrapTheoryR0) other).digest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(components, digest);
    }
}
