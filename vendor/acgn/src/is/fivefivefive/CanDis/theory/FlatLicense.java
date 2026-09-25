package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Objects;

/** Explicit recursive same-head splice license; unrelated to variadic arity. */
public final class FlatLicense {
    private static final FlatLicense NONE = new FlatLicense(null);

    private final PortPath path;

    private FlatLicense(PortPath path) {
        this.path = path;
    }

    public static FlatLicense none() {
        return NONE;
    }

    public static FlatLicense atRootPort(int portIndex) {
        return new FlatLicense(PortPath.at(portIndex));
    }

    public boolean enabled() {
        return path != null;
    }

    public PortPath path() {
        if (path == null) {
            throw new IllegalStateException("A nonflat operator has no flat path");
        }
        return path;
    }

    public StructuralKey structuralKey() {
        return StructuralKey.of(
                "flat-license",
                Collections.singletonList(path == null ? "none" : path.toString()),
                Collections.emptyList());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FlatLicense
                && Objects.equals(path, ((FlatLicense) other).path);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(path);
    }

    @Override
    public String toString() {
        return path == null ? "nonflat" : "flat@" + path;
    }
}
