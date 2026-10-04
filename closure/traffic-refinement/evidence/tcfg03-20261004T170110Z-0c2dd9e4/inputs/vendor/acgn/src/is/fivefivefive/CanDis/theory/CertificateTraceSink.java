package is.fivefivefive.CanDis.theory;

/** Optional observer of successful exact-graph transitions. */
public interface CertificateTraceSink {
    boolean enabled();

    void append(CertificateTraceEvent event);
}
