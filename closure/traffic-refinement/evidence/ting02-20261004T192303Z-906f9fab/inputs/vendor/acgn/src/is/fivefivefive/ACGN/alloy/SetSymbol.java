package is.fivefivefive.ACGN.alloy;

public abstract class SetSymbol extends AbstractSymbol {
    // parent class of SigSymbol and FieldRelation
    @Override
    public int getMaxDownlinks() {
        return 0; // SetSymbol does not have downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // SetSymbol does not have downlinks, so this method does nothing
    }
}
