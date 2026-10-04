package is.fivefivefive.ACGN.alloy;

/** A callee-edge target carrying the declaration identity checked by CALL. */
public interface CallableTargetSymbol extends Symbol {
    boolean matchesCall(CallSymbol call);
}
