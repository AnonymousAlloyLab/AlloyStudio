package is.fivefivefive.ACGN.util;

public class Hasher {
    public static int hashByTwo(int x, int y) {
        return (int) (0.5 * (x + y) * (x + y + 1)  + y);
    }
}
