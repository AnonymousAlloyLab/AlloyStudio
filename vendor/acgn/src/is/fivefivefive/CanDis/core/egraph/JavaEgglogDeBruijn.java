package is.fivefivefive.CanDis.core.egraph;

/** Variadic Java egglog arm whose stored witnesses use De Bruijn indices. */
public final class JavaEgglogDeBruijn implements AblationEngine {
    private final JavaEgglog delegate = new JavaEgglog(true);

    @Override
    public Result compare(AlloyTerm left, AlloyTerm right) {
        return delegate.compare(left, right);
    }
}
