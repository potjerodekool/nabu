/** Recursion and a loop in a method that is called once: time it with and without NABU_JIT=0 (no timer in the interpreter). */
public class JitBench {
    static int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
    static long loop(int n) { long s = 0; for (int i = 0; i < n; i++) s += (i ^ (i >> 3)) % 7; return s; }
    public static void main(String[] a) {
        System.out.println(fib(27) + "|" + loop(3000000));
    }
}
