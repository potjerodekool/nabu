/** Hot numeric static methods: compiled by libnabu_jit when it is there, interpreted otherwise; the results are the same. */
public class JitTest {
    static int sum(int n) { int s = 0; for (int i = 1; i <= n; i++) s += i; return s; }
    static long fact(int n) { long r = 1; for (int i = 2; i <= n; i++) r *= i; return r; }
    static double mix(double a, int b, long c) { return a * b + c / 4.0; }
    static int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
    static int div(int a, int b) { return a / b; }
    static int rem(int a, int b) { return a % b; }
    static int shifts(int a) { return (a << 3) ^ (a >>> 2) ^ (a >> 1); }
    static float fl(float a, float b) { return a / b + 1.5f; }
    static int cast(double d) { return (int) d + (int) (long) d; }

    public static void main(String[] args) {
        int acc = 0; long lacc = 0; double dacc = 0;
        for (int i = 0; i < 300; i++) {
            acc += sum(i % 50) + shifts(i) + cast(i * 1.5);
            lacc += fact(i % 15);
            dacc += mix(i, 3, 100L + i);
            acc += rem(i + 7, 5);
        }
        System.out.println(acc + "|" + lacc + "|" + dacc + "|" + fib(18) + "|" + fl(7f, 2f));
        try { System.out.println(div(1, 0)); } catch (ArithmeticException e) { System.out.println("div0"); }
        System.out.println(div(Integer.MIN_VALUE, -1) + "|" + rem(Integer.MIN_VALUE, -1));
    }
}
