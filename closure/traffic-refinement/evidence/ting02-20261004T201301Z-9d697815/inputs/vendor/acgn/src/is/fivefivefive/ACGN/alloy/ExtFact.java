package is.fivefivefive.ACGN.alloy;

public class ExtFact {
    // Assumptions that counts as facts, 
    // Including explicitly defined facts and facts in signature or field relations
    private boolean isExplicit;
    // Just keep the code snippets for the facts
    private String factCode;
    public ExtFact(boolean isExplicit, String factCode) {
        this.isExplicit = isExplicit;
        this.factCode = factCode;
    }
    public boolean isExplicit() {
        return isExplicit;
    }
    public String getFactCode() {
        return factCode;
    }
    @Override
    public String toString() {
        return factCode;
    }
    @Override
    public boolean equals(Object o) {
        if (o instanceof ExtFact) {
            ExtFact ef = (ExtFact) o;
            return ef.getFactCode().equals(factCode) && ef.isExplicit() == isExplicit;
        }
        return false;
    }
    @Override
    public int hashCode() {
        return factCode.hashCode() + (isExplicit ? 1 : 0);
    }
    public String getType() {
        return "ExtFact";
    }
    public String getName() {
        return factCode;
    }

}
