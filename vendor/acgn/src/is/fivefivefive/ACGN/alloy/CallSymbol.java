package is.fivefivefive.ACGN.alloy;

import java.util.Objects;

/** Semantic identity of one Alloy predicate or function call operator. */
public final class CallSymbol extends AbstractSymbol {
    public enum Kind {
        FORMULA,
        EXPRESSION
    }

    public enum ArityAuthority {
        DECLARATION,
        TYPECHECKED_IMPORT
    }

    private final Kind kind;
    private final String sourceName;
    private final String callee;
    private final int declaredArity;
    private final long occurrenceId;
    private final ArityAuthority arityAuthority;

    public CallSymbol(
            Kind kind,
            String sourceName,
            String callee,
            int declaredArity,
            long occurrenceId,
            ArityAuthority arityAuthority) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.callee = Objects.requireNonNull(callee, "callee");
        if (!ExactAlloyType.isAdmittedIdentity(sourceName)
                || !ExactAlloyType.isAdmittedIdentity(callee)) {
            throw new IllegalArgumentException(
                    "Call callee must be a well-formed visible identity");
        }
        int separator = callee.lastIndexOf('/');
        if (separator <= 0 || separator + 1 >= callee.length()) {
            throw new IllegalArgumentException(
                    "Call semantic identity must be qualified: " + callee);
        }
        if (declaredArity < 0) {
            throw new IllegalArgumentException("Call arity must be nonnegative");
        }
        this.declaredArity = declaredArity;
        if (occurrenceId < 0) {
            throw new IllegalArgumentException("Call occurrence id must be nonnegative");
        }
        this.occurrenceId = occurrenceId;
        this.arityAuthority = Objects.requireNonNull(arityAuthority, "arityAuthority");
    }

    public Kind getKind() {
        return kind;
    }

    public String getCallee() {
        return callee;
    }

    public String getSourceName() {
        return sourceName;
    }

    public int getDeclaredArity() {
        return declaredArity;
    }

    public long getOccurrenceId() {
        return occurrenceId;
    }

    public boolean isDeclarationBound() {
        return arityAuthority == ArityAuthority.DECLARATION;
    }

    public ArityAuthority getArityAuthority() {
        return arityAuthority;
    }

    public boolean matchesTarget(Symbol target) {
        return target instanceof CallableTargetSymbol
                && ((CallableTargetSymbol) target).matchesCall(this);
    }

    /** Occurrence-free key for corpus/operator aggregation only. */
    public CallSymbol semanticOperator() {
        return new CallSymbol(
                kind, sourceName, callee, declaredArity, 0, arityAuthority);
    }

    @Override
    public String getName() {
        return callee;
    }

    @Override
    public String getType() {
        return "call/" + kind.name().toLowerCase();
    }

    @Override
    public boolean isEndSymbol() {
        return false;
    }

    @Override
    public int getMaxDownlinks() {
        return declaredArity + 2;
    }

    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        if (maxDownlinks != declaredArity + 2) {
            throw new IllegalArgumentException(
                    "A call has exactly callee + arguments + terminator downlinks");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CallSymbol)) {
            return false;
        }
        CallSymbol symbol = (CallSymbol) other;
        return kind == symbol.kind
                && declaredArity == symbol.declaredArity
                && occurrenceId == symbol.occurrenceId
                && arityAuthority == symbol.arityAuthority
                && sourceName.equals(symbol.sourceName)
                && callee.equals(symbol.callee);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                kind, sourceName, callee, declaredArity, occurrenceId, arityAuthority);
    }

    @Override
    public String toString() {
        return "CallSymbol{" + kind + " " + callee + "/" + declaredArity
                + "@" + occurrenceId + "}";
    }
}
