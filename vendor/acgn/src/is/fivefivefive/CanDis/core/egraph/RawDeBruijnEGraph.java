package is.fivefivefive.CanDis.core.egraph;

/** Fixed-arity e-graph whose stored witnesses use De Bruijn variable indices. */
public final class RawDeBruijnEGraph implements AblationEngine {
    private final RawEGraph delegate = new RawEGraph(true);

    @Override
    public Result compare(AlloyTerm left, AlloyTerm right) {
        return delegate.compare(left, right);
    }
}
