package is.fivefivefive.ACGN.alloy;

public class ConstSymbol extends AbstractSymbol {
    public static final String BUILTIN_IDEN_IDENTITY = "alloy/builtin/iden";

    public enum Kind {
        BOOLEAN,
        INTEGER,
        BUILTIN_IDEN
    }

    private String name;
    private boolean isBoolean;
    private boolean isIden;
    private final Kind kind;

    public ConstSymbol(String n, boolean b, boolean iden) {
        this(n, b, iden, b ? Kind.BOOLEAN : Kind.INTEGER);
    }

    private ConstSymbol(String n, boolean b, boolean iden, Kind kind) {
        name = n;
        isBoolean = b;
        isIden = iden;
        this.kind = java.util.Objects.requireNonNull(kind, "constant kind");
    }

    /** Parser extraction factory for Alloy's reserved identity relation. */
    public static ConstSymbol builtinIden() {
        return new ConstSymbol("iden", false, true, Kind.BUILTIN_IDEN);
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isBuiltinIdentityRelation() {
        return kind == Kind.BUILTIN_IDEN
                && isIden
                && "iden".equals(name);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getType() {
        return isBoolean ? "boolean" : isIden ? "iden" : "int";
    }

    @Override
    public boolean isEndSymbol() {
        return false;
    }
    
    @Override
    public boolean equals(Object o) {
        if (o instanceof ConstSymbol) {
            ConstSymbol other = (ConstSymbol) o;
            return this.name.equals(other.getName());
        } else {
            return false;
        }
    }
    @Override
    public int hashCode() {
        return name.hashCode();
    }
    @Override
    public int getMaxDownlinks() {
        return 0; // ConstSymbol does not have downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // ConstSymbol does not have downlinks, so this method does nothing
    }
}
