package is.fivefivefive.CanDis.theory;

import java.util.List;

/** Declared theory identity; bundle-specific axioms remain under its external pin. */
public final class CertificateTheoryManifest {
    public static final String THEORY_ID = "acgn-exact-alloy-theory-v2";
    public static final String RULE_SET = "phase-j-proof-kernel-v3";
    public static final String VOCABULARY_POLICY =
            "typed-content-addressed-uninterpreted-vocabulary-v1";
    public static final String VERSION = THEORY_ID + ";" + RULE_SET + ";"
            + VOCABULARY_POLICY;

    private CertificateTheoryManifest() {
    }

    public static List<String> scalars() {
        return List.of(THEORY_ID, RULE_SET, VOCABULARY_POLICY);
    }
}
