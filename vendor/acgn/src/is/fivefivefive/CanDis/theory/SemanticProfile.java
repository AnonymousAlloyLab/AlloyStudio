package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

/** Exact semantic environment under which operator laws are admissible. */
public final class SemanticProfile {
    public static final String SOURCE_COMMAND_CONTEXT_TAG =
            "alloy-source-command-context-v1";
    public static final String PRODUCTION_ADAPTER_VERSION =
            "typed-alloy-normal-form-adapter-v13";
    public static final String PRODUCTION_REWRITE_MODE =
            "repaired-normal-form-v3;" + PRODUCTION_ADAPTER_VERSION;
    public static final String PRODUCTION_SIGNATURE_VERSION =
            "canonical-alloy-signature-v8";

    public enum OverflowMode {
        FORBID,
        MODULAR
    }

    private enum Authority {
        CUSTOM,
        FIXED_COMPATIBILITY,
        PARSED_SOURCE_COMMAND
    }

    private final int bitwidth;
    private final OverflowMode overflowMode;
    private final String temporalMode;
    private final String rewriteMode;
    private final String signatureVersion;
    private final Authority authority;
    private final StructuralKey structuralKey;
    private final String fingerprint;

    private static final SemanticProfile ALLOY_OVERFLOW_FORBIDDING =
            new SemanticProfile(
                    4,
                    OverflowMode.FORBID,
                    "alloy-temporal",
                    "repaired-normal-form-v2",
                    "alloy-signature-v2",
                    Authority.FIXED_COMPATIBILITY);
    private static final SemanticProfile ALLOY_MODULAR =
            new SemanticProfile(
                    4,
                    OverflowMode.MODULAR,
                    "alloy-temporal",
                    "repaired-normal-form-v2",
                    "alloy-signature-v2",
                    Authority.FIXED_COMPATIBILITY);

    public SemanticProfile(
            int bitwidth,
            OverflowMode overflowMode,
            String temporalMode,
            String rewriteMode,
            String signatureVersion) {
        this(
                bitwidth,
                overflowMode,
                temporalMode,
                rewriteMode,
                signatureVersion,
                Authority.CUSTOM);
    }

    private SemanticProfile(
            int bitwidth,
            OverflowMode overflowMode,
            String temporalMode,
            String rewriteMode,
            String signatureVersion,
            Authority authority) {
        if (bitwidth < 0 || bitwidth > 30) {
            throw new IllegalArgumentException("Bitwidth must be in [0,30]");
        }
        this.bitwidth = bitwidth;
        this.overflowMode = Objects.requireNonNull(overflowMode, "overflowMode");
        this.temporalMode = requireText(temporalMode, "temporalMode");
        this.rewriteMode = requireText(rewriteMode, "rewriteMode");
        this.signatureVersion = requireText(signatureVersion, "signatureVersion");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.structuralKey = StructuralKey.of(
                "semantic-profile",
                Arrays.asList(
                        Integer.toString(bitwidth),
                        overflowMode.name(),
                        this.temporalMode,
                        this.rewriteMode,
                        this.signatureVersion),
                Collections.emptyList());
        this.fingerprint = sha256(structuralKey.stableString());
    }

    public static SemanticProfile alloyOverflowForbidding() {
        return ALLOY_OVERFLOW_FORBIDDING;
    }

    public static SemanticProfile alloyModular() {
        return ALLOY_MODULAR;
    }

    /**
     * Constructs a profile from a canonical parser/execution context. The
     * adapter that owns the Alloy parser is responsible for constructing the
     * context key; this parser-independent value preserves it without reducing
     * it to a fixed label or a digest-only surrogate.
     */
    static SemanticProfile fromSourceCommand(
            int bitwidth,
            OverflowMode overflowMode,
            StructuralKey sourceCommandContext) {
        Objects.requireNonNull(sourceCommandContext, "sourceCommandContext");
        if (!SOURCE_COMMAND_CONTEXT_TAG.equals(sourceCommandContext.tag())) {
            throw new IllegalArgumentException(
                    "Source-command context has the wrong structural-key tag");
        }
        return new SemanticProfile(
                bitwidth,
                overflowMode,
                sourceCommandContext.stableString(),
                PRODUCTION_REWRITE_MODE,
                PRODUCTION_SIGNATURE_VERSION,
                Authority.PARSED_SOURCE_COMMAND);
    }

    public boolean isSourceCommandBound() {
        return authority == Authority.PARSED_SOURCE_COMMAND;
    }

    public boolean isAuthorizedAlloyProfile() {
        return authority == Authority.PARSED_SOURCE_COMMAND
                && PRODUCTION_REWRITE_MODE.equals(rewriteMode)
                && PRODUCTION_SIGNATURE_VERSION.equals(signatureVersion);
    }

    /** Compatibility profiles are admissible internally but carry no publication authority. */
    public boolean isAdmissibleAlloyProfile() {
        return authority == Authority.FIXED_COMPATIBILITY
                || isAuthorizedAlloyProfile();
    }

    public boolean isFixedCompatibilityProfile() {
        return authority == Authority.FIXED_COMPATIBILITY;
    }

    public void requireCertificateExportAuthority(boolean testOnly) {
        if (isAuthorizedAlloyProfile()) {
            return;
        }
        if (testOnly && isFixedCompatibilityProfile()) {
            return;
        }
        throw new IllegalStateException(
                "Certificate publication requires one parser-owned Alloy source command");
    }

    public int bitwidth() {
        return bitwidth;
    }

    public OverflowMode overflowMode() {
        return overflowMode;
    }

    public String temporalMode() {
        return temporalMode;
    }

    public String rewriteMode() {
        return rewriteMode;
    }

    public String signatureVersion() {
        return signatureVersion;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    public String fingerprint() {
        return fingerprint;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                result.append(String.format("%02x", part & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SemanticProfile
                && structuralKey.equals(((SemanticProfile) other).structuralKey);
    }

    @Override
    public int hashCode() {
        return structuralKey.hashCode();
    }
}
