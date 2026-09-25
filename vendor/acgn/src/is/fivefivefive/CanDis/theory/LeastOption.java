package is.fivefivefive.CanDis.theory;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Explicit unset/present minimum state under one deterministic total order. */
final class LeastOption<T> {
    private final Comparator<? super T> order;
    private Optional<T> value = Optional.empty();
    private long considered;

    LeastOption(Comparator<? super T> order) {
        this.order = Objects.requireNonNull(order, "order");
    }

    void consider(T candidate) {
        T checked = Objects.requireNonNull(candidate, "candidate");
        considered = Math.addExact(considered, 1L);
        if (value.isEmpty()) {
            value = Optional.of(checked);
            return;
        }
        T current = value.orElseThrow();
        int compared = order.compare(checked, current);
        if (compared == 0 && !checked.equals(current)) {
            throw new IllegalStateException(
                    "Deterministic minimum order equates unequal candidates");
        }
        if (compared < 0) {
            value = Optional.of(checked);
        }
    }

    T orElseThrow(Supplier<? extends RuntimeException> exception) {
        return value.orElseThrow(exception);
    }

    boolean isPresent() {
        return value.isPresent();
    }

    long considered() {
        return considered;
    }
}
