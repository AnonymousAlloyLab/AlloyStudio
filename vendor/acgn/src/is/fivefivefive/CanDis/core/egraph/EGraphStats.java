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
    /** Null when an engine does not provide this observation. */
    public final java.util.Map<String, Long> slotsByType;

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
        this(eclasses, enodes, unions, rebuilds, rewriteApplications, iterations,
                slots, slotMappings, redundantSlots, estimatedBytes, null);
    }

    public EGraphStats(long eclasses, long enodes, long unions, long rebuilds,
            long rewriteApplications, long iterations, long slots, long slotMappings,
            long redundantSlots, long estimatedBytes, java.util.Map<String, Long> slotsByType) {
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
        this.slotsByType = slotsByType == null ? null : java.util.Map.copyOf(slotsByType);
        if (slotsByType != null && slotsByType.values().stream().mapToLong(Long::longValue).sum() != slots) {
            throw new IllegalArgumentException("Interface slot counts do not sum to total");
        }
    }

    public static EGraphStats empty() {
        return new EGraphStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
