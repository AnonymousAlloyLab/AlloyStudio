package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Positive identity memo owned by one synchronous adapter invocation. */
final class CertificateVerificationScope implements AutoCloseable {
    static final int CAPACITY = 8192;
    private static final ThreadLocal<CertificateVerificationScope> CURRENT = new ThreadLocal<>();
    private final CertificateVerificationScope previous;
    final Set<TypedEqualityCertificate> visiting = identities();
    private Set<TypedEqualityCertificate> verified = identities();
    private List<String> policy = policy();

    private CertificateVerificationScope() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static CertificateVerificationScope open() { return new CertificateVerificationScope(); }
    static CertificateVerificationScope current() { return CURRENT.get(); }
    private static Set<TypedEqualityCertificate> identities() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static List<String> policy() {
        return java.util.Arrays.asList(System.getProperty("acgn.maxGroupElements"),
                System.getProperty("acgn.maxGlobalRenamings"),
                System.getProperty("acgn.maxCanonicalRecursionDepth"),
                System.getProperty("acgn.maxCanonicalWorkItems"),
                System.getProperty("acgn.maxGroupPresentationWork"));
    }

    Set<TypedEqualityCertificate> verified() {
        var currentPolicy = policy();
        if (!policy.equals(currentPolicy) || verified.size() >= CAPACITY) {
            // Discarding the memo only causes ordinary verification again.
            verified = identities();
            policy = currentPolicy;
        }
        return verified;
    }

    @Override public void close() {
        if (CURRENT.get() != this) throw new IllegalStateException("Verification scope ownership");
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        visiting.clear();
        verified.clear();
    }
}
