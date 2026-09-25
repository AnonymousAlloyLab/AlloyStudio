package is.fivefivefive.ACGN.alloy;

public final class EndSymbol extends AbstractSymbol {
    public EndSymbol() {}
    public String getName() {
        return "<END>";
    }
    public String getType() {
        return "SYMBOL_END";
    }
    public boolean isEndSymbol() {
        return true;
    }
    public boolean equals(EndSymbol another) {
        return true;
    }
    public int hashCode() {
        return 0;
    }
    @Override
    public int getMaxDownlinks() {
        return 0; // EndSymbol does not have downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // EndSymbol does not have downlinks, so this method does nothing
    }
}
