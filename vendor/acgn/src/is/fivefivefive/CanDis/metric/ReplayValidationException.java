package is.fivefivefive.CanDis.metric;

/** A failed explicit replay obligation, distinct from an unexpected checker error. */
public class ReplayValidationException extends IllegalArgumentException {
    public ReplayValidationException(String message) { super(message); }
    public ReplayValidationException(String message, Throwable cause) { super(message, cause); }
}
