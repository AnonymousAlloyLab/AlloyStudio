package is.fivefivefive.ACGN.alloy;

import is.fivefivefive.ACGN.asg.AugmentedNode;

public class PredRootSymbol extends AbstractSymbol implements CallableTargetSymbol {
    private AugmentedNode node;
    private String name;
    private final String semanticIdentity;
    private final CallSymbol.Kind callKind;
    private final int declaredArity;
    private final CallSymbol.ArityAuthority arityAuthority;

    public PredRootSymbol(AugmentedNode n, String nm) {
        this(n, nm, null, null, -1, null);
    }

    public PredRootSymbol(
            AugmentedNode n,
            String nm,
            String semanticIdentity,
            CallSymbol.Kind callKind,
            int declaredArity,
            CallSymbol.ArityAuthority arityAuthority) {
        node = n;
        name = nm;
        this.semanticIdentity = semanticIdentity;
        this.callKind = callKind;
        this.declaredArity = declaredArity;
        this.arityAuthority = arityAuthority;
    }
    @Override
    public String getName() {
        return name;
    }
    @Override
    public String getType() {
        return "predroot";
    }
    @Override
    public boolean isEndSymbol() {
        return false;
    }
    public AugmentedNode getNode() {
        return node;
    }
    @Override
    public boolean matchesCall(CallSymbol call) {
        return semanticIdentity != null
                && semanticIdentity.equals(call.getCallee())
                && name.equals(call.getSourceName())
                && callKind == call.getKind()
                && declaredArity == call.getDeclaredArity()
                && arityAuthority == call.getArityAuthority();
    }
    @Override
    public int getMaxDownlinks() {
        return -1; // PredRootSymbol can have any number of downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // PredRootSymbol does not have a fixed number of downlinks, so this method does nothing 
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PredRootSymbol)) return false;
        PredRootSymbol that = (PredRootSymbol) o;
        return name.equals(that.name)
                && java.util.Objects.equals(semanticIdentity, that.semanticIdentity)
                && callKind == that.callKind
                && declaredArity == that.declaredArity
                && arityAuthority == that.arityAuthority;
    }
    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                name, semanticIdentity, callKind, declaredArity, arityAuthority);
    }
}
