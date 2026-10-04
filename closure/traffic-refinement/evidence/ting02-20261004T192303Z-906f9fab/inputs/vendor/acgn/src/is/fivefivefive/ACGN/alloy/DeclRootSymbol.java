package is.fivefivefive.ACGN.alloy;

public class DeclRootSymbol extends AbstractSymbol {
    private int semantic;
    // private SetSymbol sigType;
    public DeclRootSymbol(int semantic) {
        this.semantic = semantic;
    }
    /* 
    public DeclRootSymbol(int semantic, SetSymbol sigType) {
        this(semantic);
        setSigType(sigType);
    }*/
    private String getSemanticString() {
        switch (semantic) {
            case 0:
                return "default";
            case 1:
                return "disj";
            case 2:
                return "var";
            case 3:
                return "var disj";
        }
        return "unknown";
    }
    public String getName() {
        return "DECLROOT_" + getSemanticString();
    }
    public String getType() {
        return getName();
    }
    public boolean isEndSymbol() {
        return false;
    }
    @Override
    public int getMaxDownlinks() {
        return -1; // DeclRootSymbol can have any number of downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // DeclRootSymbol does not have a fixed number of downlinks, so this method does nothing
    }
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DeclRootSymbol)) return false;
        DeclRootSymbol that = (DeclRootSymbol) o;
        return semantic == that.semantic;
    }
    @Override
    public int hashCode() {
        return 96601 + semantic;
    }
    @Override
    public String toString() {
        return "DeclRootSymbol{" +
                "semantic=" + semantic +
                '}';
    }
    /* 
    public SetSymbol getSigType() {
        return sigType;
    }
    public void setSigType(SetSymbol sig) {
        sigType = sig;
    }*/
}
