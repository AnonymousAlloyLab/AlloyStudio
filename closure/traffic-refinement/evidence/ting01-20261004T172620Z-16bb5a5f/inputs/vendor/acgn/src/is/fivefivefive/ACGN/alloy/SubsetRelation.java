package is.fivefivefive.ACGN.alloy;


public class SubsetRelation extends RelationSet {
    private boolean isExtends; // true if the subset relation requires muturally exclusive with other subsets; - identity-mapping
    public SubsetRelation(String n, SigSymbol s, SigSymbol t, boolean isExt) {
        super(n, s, t);
        isExtends = isExt;
    }
    public boolean isExtends() {
        return isExtends;
    }
}
