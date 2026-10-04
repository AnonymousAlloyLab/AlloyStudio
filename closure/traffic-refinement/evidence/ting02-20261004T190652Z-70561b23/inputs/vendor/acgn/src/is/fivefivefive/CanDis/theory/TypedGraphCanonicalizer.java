package is.fivefivefive.CanDis.theory;

/** Graph-relative canonicalization boundary used by the exact engine. */
public interface TypedGraphCanonicalizer {
    CanonicalizationResult canonicalize(TypedSlottedPortEGraph graph, TypedENode node);

    String version();
}
