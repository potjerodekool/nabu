/** Sieve and matrix loops over arrays, called once each: time with and without NABU_JIT=0. */
public class JitArrayBench {
    static int sieve(int n) {
        boolean[] c = new boolean[n + 1];
        int count = 0;
        for (int i = 2; i <= n; i++) {
            if (!c[i]) { count++; for (int j = i * 2; j <= n; j += i) c[j] = true; }
        }
        return count;
    }
    static long matrix(int n) {
        long[] a = new long[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) a[i * n + j] = i ^ j;
        long s = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) s += a[i * n + j] * a[j * n + i];
        return s;
    }
    public static void main(String[] args) {
        System.out.println(sieve(2000000) + "|" + matrix(300));
    }
}
