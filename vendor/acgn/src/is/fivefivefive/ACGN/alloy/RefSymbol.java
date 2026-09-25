package is.fivefivefive.ACGN.alloy;

import is.fivefivefive.ACGN.asg.AugmentedNode;

public class RefSymbol extends AbstractSymbol implements CallableTargetSymbol {
    private AugmentedNode node;
    private String name;
    private String type;
    private boolean isEnd;
    private String semanticIdentity;
    private CallSymbol.Kind callKind;
    private int declaredArity = -1;
    private CallSymbol.ArityAuthority arityAuthority;
    public RefSymbol(AugmentedNode n, String nm) {
        node = n;
        name = nm;
        type = ""; // TODO: get type from node
    }
    public RefSymbol(
            AugmentedNode n,
            String nm,
            String semanticIdentity,
            CallSymbol.Kind callKind,
            int declaredArity,
            CallSymbol.ArityAuthority arityAuthority) {
        this(n, nm);
        this.semanticIdentity = semanticIdentity;
        this.callKind = callKind;
        this.declaredArity = declaredArity;
        this.arityAuthority = arityAuthority;
    }
    public RefSymbol(boolean end) {
        isEnd = end;
        node = new AugmentedNode(-1, 0);
        name = "<END>";
    }
    @Override
    public String getName() {
        return name;
    }
    @Override
    public String getType() {
        return type;
    }
    public void setType(String t) {
        type = t;
    }
    @Override
    public boolean isEndSymbol() {
        return isEnd;
    }
    public AugmentedNode getNode() {
        return node;
    }
    public void setNode(AugmentedNode node) {
        this.node = node;
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
        return -1; // RefSymbol can have any number of downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // RefSymbol does not have a fixed number of downlinks, so this method does nothing 
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefSymbol)) return false;
        RefSymbol that = (RefSymbol) o;
        return isEnd == that.isEnd
                && declaredArity == that.declaredArity
                && name.equals(that.name)
                && java.util.Objects.equals(type, that.type)
                && java.util.Objects.equals(semanticIdentity, that.semanticIdentity)
                && callKind == that.callKind
                && arityAuthority == that.arityAuthority;
    }
    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + (isEnd ? 1 : 0);
        result = 31 * result + java.util.Objects.hashCode(semanticIdentity);
        result = 31 * result + java.util.Objects.hashCode(callKind);
        result = 31 * result + declaredArity;
        result = 31 * result + java.util.Objects.hashCode(arityAuthority);
        return result;
    }
}
