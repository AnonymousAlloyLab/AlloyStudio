package is.fivefivefive.CanDis.theory;

/** Allocation-free default used by ordinary construction and experiments. */
public final class NoOpCertificateTraceSink implements CertificateTraceSink {
    private static final NoOpCertificateTraceSink INSTANCE =
            new NoOpCertificateTraceSink();

    private NoOpCertificateTraceSink() {
    }

    public static NoOpCertificateTraceSink instance() {
        return INSTANCE;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void append(CertificateTraceEvent event) {
        // Deliberately empty.
    }
}
