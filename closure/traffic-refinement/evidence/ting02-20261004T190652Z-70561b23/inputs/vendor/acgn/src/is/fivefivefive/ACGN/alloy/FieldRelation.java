package is.fivefivefive.ACGN.alloy;

import java.util.Set;

public class FieldRelation extends RelationSet {
    private Set<FieldConfiner> confiners; // one, lone, some, ...
    public FieldRelation(String n, SigSymbol s, SetSymbol t, Set<FieldConfiner> conf) {
        super(n, s, t);
        confiners = conf;
    }
    public Set<FieldConfiner> getConfiners() {
        return confiners;
    }
    @Override
    public String getType() {
        return "FieldRelation :" + confiners.toString();
    }
    @Override
    public int getMaxDownlinks() {
        return 0; // FieldRelation has at most one downlink
    }
    public void setMaxDownlinks(int maxDownlinks) {
        // FieldRelation does not have downlinks, so this method does nothing
    }
}
