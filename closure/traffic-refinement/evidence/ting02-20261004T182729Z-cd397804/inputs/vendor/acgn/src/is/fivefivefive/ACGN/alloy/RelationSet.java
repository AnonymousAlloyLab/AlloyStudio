package is.fivefivefive.ACGN.alloy;


public abstract class RelationSet extends SetSymbol {
    private String name;
    private SigSymbol source;
    private SetSymbol target;
    public RelationSet(String n, SigSymbol s, SetSymbol t) {
        name = n;
        source = s;
        target = t;
    }
    @Override
    public String getName() {
        return name;
    }
    @Override
    public String getType() {
        return "Relation";
    }
    @Override
    public boolean isEndSymbol() {
        return false;
    }
    public SigSymbol getSource() {
        return source;
    }
    public SetSymbol getTarget() {
        return target;
    }
}
