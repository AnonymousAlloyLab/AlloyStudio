package is.fivefivefive.CanDis.theory;

/** Signals a canon_G pre/postcondition conflict instead of weakening typed support. */
public final class CanonicalizationDomainException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final boolean resourceLimit;

    CanonicalizationDomainException(String message) {
        this(message,false);
    }

    private CanonicalizationDomainException(String message,boolean resourceLimit) {
        super(message);
        this.resourceLimit=resourceLimit;
    }

    static CanonicalizationDomainException resourceLimit(String message) {
        return new CanonicalizationDomainException(message,true);
    }

    public boolean isResourceLimit() { return resourceLimit; }
}
