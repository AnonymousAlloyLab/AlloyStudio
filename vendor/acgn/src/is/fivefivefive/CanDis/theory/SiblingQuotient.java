package is.fivefivefive.CanDis.theory;

/** Equality imposed among siblings independently of recursive flattening. */
public enum SiblingQuotient {
    ORDERED_SEQUENCE(false, false),
    COMMUTATIVE_BAG(true, false),
    COMMUTATIVE_IDEMPOTENT_SET(true, true);

    private final boolean commutative;
    private final boolean idempotent;

    SiblingQuotient(boolean commutative, boolean idempotent) {
        this.commutative = commutative;
        this.idempotent = idempotent;
    }

    public boolean commutative() {
        return commutative;
    }

    public boolean idempotent() {
        return idempotent;
    }
}
