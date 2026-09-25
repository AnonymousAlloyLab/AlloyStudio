package is.fivefivefive.CanDis.theory;

import java.util.Objects;

/** Location of a schema within one operator port's unary schema tree. */
public final class PortPath implements Comparable<PortPath> {
    private final int portIndex;
    private final int depth;

    private PortPath(int portIndex, int depth) {
        if (portIndex < 0 || depth < 0) {
            throw new IllegalArgumentException("Port path components must be non-negative");
        }
        this.portIndex = portIndex;
        this.depth = depth;
    }

    public static PortPath at(int portIndex) {
        return new PortPath(portIndex, 0);
    }

    public PortPath child() {
        return new PortPath(portIndex, Math.addExact(depth, 1));
    }

    public int portIndex() {
        return portIndex;
    }

    public int depth() {
        return depth;
    }

    @Override
    public int compareTo(PortPath other) {
        Objects.requireNonNull(other, "other");
        int compared = Integer.compare(portIndex, other.portIndex);
        return compared != 0 ? compared : Integer.compare(depth, other.depth);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PortPath
                && portIndex == ((PortPath) other).portIndex
                && depth == ((PortPath) other).depth;
    }

    @Override
    public int hashCode() {
        return 31 * portIndex + depth;
    }

    @Override
    public String toString() {
        return portIndex + "/" + depth;
    }
}
