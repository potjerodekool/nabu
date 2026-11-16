/** Long and double parameters take two local variable slots in the bytecode (lload_0, iload_2). */
public class WideParamsTest {
    static double add(double a, double b) { return a + b; }
    static long mix(long a, int b, long c) { return a + b + c; }
    static double scale(int n, double x) { return n * x; }

    public static void main(String[] args) {
        System.out.println(add(1.5, 2.25));
        System.out.println(mix(10L, 5, 100L));
        System.out.println(scale(3, 0.5));
    }
}
