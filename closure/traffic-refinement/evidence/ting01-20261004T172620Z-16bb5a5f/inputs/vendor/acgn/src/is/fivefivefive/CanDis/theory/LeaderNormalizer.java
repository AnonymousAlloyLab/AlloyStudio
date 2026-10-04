package is.fivefivefive.CanDis.theory;

/** Compatibility projection of the Phase DA structural leader-kernel result. */
final class LeaderNormalizer {
    private LeaderNormalizer() {
    }

    static TypedENode normalize(
            TypedSlottedPortEGraph graph,
            TypedENode node) {
        return LeaderKernelExtractor.instance()
                .extract(graph, node)
                .ambientLeaderNode();
    }
}
