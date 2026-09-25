package is.fivefivefive.CanDis.augmentation;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;

import is.fivefivefive.CanDis.theory.StructuralKey;

final class AugmentationDigests {
    private AugmentationDigests() {
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String sha256(StructuralKey value) {
        return HexFormat.of().formatHex(hashNode(
                value, new IdentityHashMap<>()));
    }

    private static byte[] hashNode(
            StructuralKey key,
            Map<StructuralKey, byte[]> memo) {
        byte[] prior = memo.get(key);
        if (prior != null) {
            return prior;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, key.tag());
            update(digest, key.scalars().size());
            for (String scalar : key.scalars()) {
                update(digest, scalar);
            }
            update(digest, key.children().size());
            for (StructuralKey child : key.children()) {
                digest.update(hashNode(child, memo));
            }
            byte[] result = digest.digest();
            memo.put(key, result);
            return result;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
