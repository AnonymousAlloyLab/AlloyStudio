package is.fivefivefive.CanDis.theory;

/** Signals a canon_G pre/postcondition conflict instead of weakening typed support. */
public final class CanonicalizationDomainException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    CanonicalizationDomainException(String message) {
        super(message);
    }
}
