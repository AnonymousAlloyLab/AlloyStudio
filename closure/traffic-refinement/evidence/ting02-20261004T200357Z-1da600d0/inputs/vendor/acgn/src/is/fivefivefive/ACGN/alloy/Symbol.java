package is.fivefivefive.ACGN.alloy;

// import is.fivefivefive.ACGN.asg.AugmentedNode;

public interface Symbol {
    public String getName();
    public String getType();
    public boolean isEndSymbol();
    public double getSignature();
    public void setSignature(double sig);
    public int getMaxDownlinks();
    public void setMaxDownlinks(int maxDownlinks);
    // public AugmentedNode toNode();
}
