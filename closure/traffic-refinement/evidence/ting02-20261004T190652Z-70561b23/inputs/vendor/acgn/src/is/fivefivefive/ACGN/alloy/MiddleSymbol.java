package is.fivefivefive.ACGN.alloy;

public class MiddleSymbol extends AbstractSymbol {
    private String name;
    private boolean infiniteRoot;
    private boolean isQt;
    private int maxDownlinks = 1; // Default value for maxDownlinks
    private boolean typeConfinerOp = false; // Default value for typeConfinerOp
    public static final String TYPECONFINEROP_PREFIX = "TYPECONFINEROP_";
    public MiddleSymbol(String name) {
        this.name = name;
        this.infiniteRoot = false; // Default value for infiniteRoot
        this.isQt = false;
        if (name.startsWith(TYPECONFINEROP_PREFIX)) {
            this.typeConfinerOp = true;
        }
    }
    public MiddleSymbol(String name, boolean infiniteRoot) {
        this.name = name;
        this.infiniteRoot = infiniteRoot;
        this.isQt = false;
    }
    public MiddleSymbol(String name, boolean isQt, boolean infiniteRoot) {
        this.name = name;
        this.infiniteRoot = infiniteRoot;
        this.isQt = isQt;
    }
    public boolean isInfiniteRoot() {
        return infiniteRoot;
    }
    public void setInfiniteRoot(boolean infiniteRoot) {
        this.infiniteRoot = infiniteRoot;
    }
    public boolean isQt() {
        return isQt;
    }
    public String getName() {
        return name;
    }
    public String getType() {
        return "MIDDLENODE_" + name;
    }
    public boolean isEndSymbol() {
        return false;
    }
    @Override
    public boolean equals(Object o) {
        if (o instanceof MiddleSymbol) {
            MiddleSymbol other = (MiddleSymbol) o;
            return this.name.equals(other.getName());
        } else {
            return false;
        }
    }
    @Override
    public int hashCode() {
        return name.hashCode() % 114493 + 114493; // closest prime number to 114514
    }
    @Override
    public String toString() {
        return "MiddleSymbol{" +
                "name='" + name + '\'' +
                ", infiniteRoot=" + infiniteRoot +
                ", typeConfinerOp= " + typeConfinerOp +
                '}';
    }
    @Override
    public int getMaxDownlinks() {
        return maxDownlinks;
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        this.maxDownlinks = maxDownlinks;
    }
    public boolean isTypeConfinerOp() {
        return typeConfinerOp;
    }
    public void setTypeConfinerOp(boolean typeConfinerOp) {
        this.typeConfinerOp = typeConfinerOp;

    }
}
