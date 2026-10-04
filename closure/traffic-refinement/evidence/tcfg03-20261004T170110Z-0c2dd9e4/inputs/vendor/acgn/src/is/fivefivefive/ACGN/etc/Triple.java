package is.fivefivefive.ACGN.etc;

import is.fivefivefive.ACGN.util.Hasher;

public class Triple<A, B, C> extends is.fivefivefive.alloyasg.etc.Triple<A, B, C> {
    public Triple(A first, B second, C third) {
        super(first, second, third);
    }
    
    public static <A, B, C> Triple<A, B, C> of(A first, B second, C third) {
        return new Triple<>(first, second, third);
    }

    @Override
    public int hashCode() {
        return Hasher.hashByTwo(Hasher.hashByTwo(x.hashCode(), y.hashCode()), z.hashCode());
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Triple) {
            Triple<?, ?, ?> other = (Triple<?, ?, ?>) obj;
            return x.equals(other.x) && y.equals(other.y) && z.equals(other.z);
        }
        return false;
    }
}
