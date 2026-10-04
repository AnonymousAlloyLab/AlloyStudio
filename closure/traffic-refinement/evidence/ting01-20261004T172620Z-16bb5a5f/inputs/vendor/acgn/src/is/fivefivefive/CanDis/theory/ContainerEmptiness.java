package is.fivefivefive.CanDis.theory;

/** The {@code epsilon} index of a variadic schema {@code K^epsilon(kappa)}. */
public enum ContainerEmptiness {
    /** {@code K+}: the empty container is outside the port grammar. */
    K_PLUS("+", false),

    /** {@code K0}: zero children are admitted; only a flat K0 needs a unit law. */
    K_ZERO("0", true);

    private final String symbol;
    private final boolean admitsEmpty;

    ContainerEmptiness(String symbol, boolean admitsEmpty) {
        this.symbol = symbol;
        this.admitsEmpty = admitsEmpty;
    }

    public String symbol() {
        return symbol;
    }

    public boolean admitsEmpty() {
        return admitsEmpty;
    }
}
