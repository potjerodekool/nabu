/** Only in the jar: compressed with deflate, so reading it needs the inflater of the runtime. */
public class JarMath {
    public static int twice(int x) {
        int sum = 0;
        for (int i = 0; i < 2; i++) {
            sum += x;
        }
        return sum;
    }
}
