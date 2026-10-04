package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/** Exact admitted sibling counts for one container port. */
public final class ArityPolicy {
    public enum Kind {
        FINITE,
        AT_LEAST
    }

    private final Kind kind;
    private final NavigableSet<Integer> finite;
    private final int minimum;

    private ArityPolicy(Kind kind, NavigableSet<Integer> finite, int minimum) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.finite = finite == null
                ? Collections.emptyNavigableSet()
                : Collections.unmodifiableNavigableSet(new TreeSet<>(finite));
        this.minimum = minimum;
    }

    public static ArityPolicy exact(int arity) {
        return finite(Collections.singletonList(arity));
    }

    public static ArityPolicy finite(Integer... arities) {
        return finite(Arrays.asList(arities));
    }

    public static ArityPolicy finite(Iterable<Integer> arities) {
        Objects.requireNonNull(arities, "arities");
        NavigableSet<Integer> admitted = new TreeSet<>();
        for (Integer arity : arities) {
            if (arity == null || arity < 0) {
                throw new IllegalArgumentException("Admitted arities must be nonnegative");
            }
            admitted.add(arity);
        }
        if (admitted.isEmpty()) {
            throw new IllegalArgumentException("An arity policy must admit at least one count");
        }
        return new ArityPolicy(Kind.FINITE, admitted, -1);
    }

    public static ArityPolicy atLeast(int minimum) {
        if (minimum < 0) {
            throw new IllegalArgumentException("Minimum arity must be nonnegative");
        }
        return new ArityPolicy(Kind.AT_LEAST, null, minimum);
    }

    public static ArityPolicy nonemptyVariadic() {
        return atLeast(1);
    }

    public static ArityPolicy zeroOrMore() {
        return atLeast(0);
    }

    public Kind kind() {
        return kind;
    }

    public boolean admits(int arity) {
        return arity >= 0 && (kind == Kind.AT_LEAST
                ? arity >= minimum
                : finite.contains(arity));
    }

    public boolean admitsZero() {
        return admits(0);
    }

    /** True when a partially built container can still reach an admitted arity. */
    public boolean canExtend(int currentArity) {
        if (currentArity < 0) {
            return false;
        }
        return kind == Kind.AT_LEAST || currentArity <= finite.last();
    }

    public void requireAdmitted(int arity, String subject) {
        if (!admits(arity)) {
            throw new IllegalStateException(
                    subject + " has arity " + arity + " outside " + this);
        }
    }

    public int minimum() {
        return kind == Kind.AT_LEAST ? minimum : finite.first();
    }

    public NavigableSet<Integer> finiteArities() {
        return finite;
    }

    public boolean isPositiveDownwardClosed() {
        if (kind == Kind.AT_LEAST) {
            return minimum <= 1;
        }
        for (int arity : finite) {
            for (int required = 1; required <= arity; required++) {
                if (!finite.contains(required)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean isFlatSpliceClosed() {
        if (kind == Kind.AT_LEAST) {
            return true;
        }
        for (int outer : finite) {
            if (outer == 0) {
                continue;
            }
            for (int nested : finite) {
                if (!finite.contains(outer + nested - 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void requirePositiveDownwardClosure(String subject) {
        if (!isPositiveDownwardClosed()) {
            throw new IllegalArgumentException(
                    subject + " requires positive downward-closed arities");
        }
    }

    public void requireFlatSpliceClosure(String subject) {
        if (!isFlatSpliceClosed()) {
            throw new IllegalArgumentException(
                    subject + " requires closure under k+l-1 flat splicing");
        }
    }

    public StructuralKey structuralKey() {
        List<String> scalars = new ArrayList<>();
        scalars.add(kind.name());
        if (kind == Kind.AT_LEAST) {
            scalars.add(Integer.toString(minimum));
        } else {
            for (int arity : finite) {
                scalars.add(Integer.toString(arity));
            }
        }
        return StructuralKey.of("arity-policy", scalars, Collections.emptyList());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ArityPolicy)) {
            return false;
        }
        ArityPolicy policy = (ArityPolicy) other;
        return kind == policy.kind
                && minimum == policy.minimum
                && finite.equals(policy.finite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, finite, minimum);
    }

    @Override
    public String toString() {
        return kind == Kind.AT_LEAST ? "K>=" + minimum : "K" + finite;
    }
}
