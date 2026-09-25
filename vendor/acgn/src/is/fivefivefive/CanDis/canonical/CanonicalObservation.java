package is.fivefivefive.CanDis.canonical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import is.fivefivefive.CanDis.theory.StructuralKey;

/** Deterministic equality, hashing, and serialization observation. */
public final class CanonicalObservation {
    private final StructuralKey key;
    private final String digest;

    public CanonicalObservation(StructuralKey key) {
        this.key = Objects.requireNonNull(key, "key");
        this.digest = sha256(key.stableString());
    }

    public StructuralKey key() {
        return key;
    }

    public String digest() {
        return digest;
    }

    public String stableForm() {
        return key.stableString();
    }

    public boolean equivalentTo(CanonicalObservation other) {
        return other != null && key.equals(other.key);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
