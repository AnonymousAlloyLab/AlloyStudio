package is.fivefivefive.ACGN.alloy;

public final class ShadowSymbol extends AbstractSymbol {
    public static final ShadowSymbol SHADOW = new ShadowSymbol();
    public ShadowSymbol() {
        super();
    }
    public String getName() {
        return "<SHADOW>";
    }
    public String getType() {
        return "SHADOW";
    }
    public boolean isEndSymbol() {
        return false;
    }
    @Override
    public int hashCode() {
        return 1145141919;
    }
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ShadowSymbol) {
            return true;
        }
        return false;
    }
    @Override
    public String toString() {
        return "ShadowSymbol{" +
                "signature=" + getSignature() +
                '}';
    }
    @Override
    public int getMaxDownlinks() {
        return 0; // ShadowSymbol does not have downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // ShadowSymbol does not have downlinks, so this method does nothing
    }
}
