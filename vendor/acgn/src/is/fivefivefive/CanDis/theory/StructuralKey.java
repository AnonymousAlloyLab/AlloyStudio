package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable, deterministic, totally ordered structural key. */
public final class StructuralKey implements Comparable<StructuralKey> {
    private final String tag;
    private final List<String> scalars;
    private final List<StructuralKey> children;
    private final int hashCode;

    private StructuralKey(String tag, List<String> scalars, List<StructuralKey> children) {
        this.tag = requireText(tag, "tag");
        this.scalars = immutableStrings(scalars);
        this.children = immutableChildren(children);
        this.hashCode = Objects.hash(this.tag, this.scalars, this.children);
    }

    public static StructuralKey of(
            String tag,
            List<String> scalars,
            List<StructuralKey> children) {
        return new StructuralKey(tag, scalars, children);
    }

    public static StructuralKey leaf(String tag, String... scalars) {
        Objects.requireNonNull(scalars, "scalars");
        return new StructuralKey(tag, Arrays.asList(scalars), Collections.emptyList());
    }

    public static StructuralKey branch(String tag, List<StructuralKey> children) {
        return new StructuralKey(tag, Collections.emptyList(), children);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    private static List<String> immutableStrings(List<String> values) {
        Objects.requireNonNull(values, "scalars");
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(Objects.requireNonNull(value, "scalar"));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<StructuralKey> immutableChildren(List<StructuralKey> values) {
        Objects.requireNonNull(values, "children");
        List<StructuralKey> result = new ArrayList<>(values.size());
        for (StructuralKey value : values) {
            result.add(Objects.requireNonNull(value, "child key"));
        }
        return Collections.unmodifiableList(result);
    }

    public String tag() {
        return tag;
    }

    public List<String> scalars() {
        return scalars;
    }

    public List<StructuralKey> children() {
        return children;
    }

    @Override
    public int compareTo(StructuralKey other) {
        Objects.requireNonNull(other, "other");
        int compared = tag.compareTo(other.tag);
        if (compared != 0) {
            return compared;
        }
        compared = compareStrings(scalars, other.scalars);
        return compared != 0 ? compared : compareChildren(children, other.children);
    }

    private static int compareStrings(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int i = 0; i < shared; i++) {
            int compared = left.get(i).compareTo(right.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static int compareChildren(List<StructuralKey> left, List<StructuralKey> right) {
        int shared = Math.min(left.size(), right.size());
        for (int i = 0; i < shared; i++) {
            int compared = left.get(i).compareTo(right.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    /** Length-prefixed serialization suitable for deterministic diagnostics and digests. */
    public String stableString() {
        StringBuilder result = new StringBuilder();
        appendEncoded(result, tag);
        result.append('[').append(scalars.size()).append(':');
        for (String scalar : scalars) {
            appendEncoded(result, scalar);
        }
        result.append(']').append('{').append(children.size()).append(':');
        for (StructuralKey child : children) {
            String encoded = child.stableString();
            result.append(encoded.length()).append(':').append(encoded);
        }
        return result.append('}').toString();
    }

    private static void appendEncoded(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StructuralKey)) {
            return false;
        }
        StructuralKey key = (StructuralKey) other;
        return tag.equals(key.tag)
                && scalars.equals(key.scalars)
                && children.equals(key.children);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return stableString();
    }
}
