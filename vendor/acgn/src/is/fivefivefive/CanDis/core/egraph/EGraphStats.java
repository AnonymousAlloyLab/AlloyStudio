package is.fivefivefive.CanDis.core.egraph;

/** Per-predicate-pair structural counters emitted by an ablation engine. */
public final class EGraphStats {
    public final long eclasses;
    public final long enodes;
    public final long unions;
    public final long rebuilds;
    public final long rewriteApplications;
    public final long iterations;
    public final long slots;
    public final long slotMappings;
    public final long redundantSlots;
    public final long estimatedBytes;

    public EGraphStats(
            long eclasses,
            long enodes,
            long unions,
            long rebuilds,
            long rewriteApplications,
            long iterations,
            long slots,
            long slotMappings,
            long redundantSlots,
            long estimatedBytes) {
        this.eclasses = eclasses;
        this.enodes = enodes;
        this.unions = unions;
        this.rebuilds = rebuilds;
        this.rewriteApplications = rewriteApplications;
        this.iterations = iterations;
        this.slots = slots;
        this.slotMappings = slotMappings;
        this.redundantSlots = redundantSlots;
        this.estimatedBytes = estimatedBytes;
    }

    public static EGraphStats empty() {
        return new EGraphStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
