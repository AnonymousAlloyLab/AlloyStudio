package is.fivefivefive.ACGN.alloy;

import java.util.List;

public class DummySymbol extends AbstractSymbol {
    // A dummy symbol representing anything of the signatures, vars, references, etc.
    private String type;
    public DummySymbol(String type) {
        this.type = type;
    }
    public String getName() {
        return "Dummy for " + getType();
    }
    public String getType() {
        return type;
    }
    public boolean isEndSymbol() {
        return false;
    }
    @Override
    public int hashCode() {
        return type.hashCode();
    }
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DummySymbol) {
            return type.equals(((DummySymbol)obj).type);
        }
        return false;
    }
    @Override
    public String toString() {
        return "DummySymbol{" +
                "type='" + type + '\'' +
                '}';
    }
    @Override
    public int getMaxDownlinks() {
        return 0; // DummySymbol does not have downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // DummySymbol does not have downlinks, so this method does nothing
    }

    public static final DummySymbol DUMMY_LOCAL_VAR = new DummySymbol("local_var");
    public static final DummySymbol DUMMY_GLOBAL_VAR = new DummySymbol("global_var");
    public static final DummySymbol DUMMY_SIG = new DummySymbol("sig");
    public static final DummySymbol DUMMY_FIELD = new DummySymbol("field");
    public static final DummySymbol DUMMY_REF = new DummySymbol("ref");
    public static final DummySymbol DUMMY_LET = new DummySymbol("let");
    public static final DummySymbol DUMMY_SUBSET = new DummySymbol("subset");
    public static final DummySymbol DUMMY_PREDROOT = new DummySymbol("predroot"); 
    public static final List<DummySymbol> ALL_DUMMIES = List.of(DUMMY_LOCAL_VAR, DUMMY_GLOBAL_VAR, DUMMY_SIG, DUMMY_FIELD, DUMMY_REF, DUMMY_LET, DUMMY_SUBSET, DUMMY_PREDROOT);
}
