/** Arrays, fields, statics, instance methods and exceptions in compiled code: the same output with and without NABU_JIT=0. */
public class JitObjTest {
    static int counter;
    static long total = 5;
    int base = 7;
    long wide = 1L << 40;
    double scale = 1.5;
    int[] data = new int[8];

    static int sieve(int n) {
        boolean[] composite = new boolean[n + 1];
        int count = 0;
        for (int i = 2; i <= n; i++) {
            if (!composite[i]) { count++; for (int j = i * 2; j <= n; j += i) composite[j] = true; }
        }
        return count;
    }
    static long dot(long[] a, double[] b) {
        long s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * (long) (b[i] * 2.0);
        return s;
    }
    static int sortAndSum(int[] a) {
        for (int i = 1; i < a.length; i++) {
            int k = a[i]; int j = i - 1;
            while (j >= 0 && a[j] > k) { a[j + 1] = a[j]; j--; }
            a[j + 1] = k;
        }
        int s = 0;
        for (int i = 0; i < a.length; i++) s = s * 31 + a[i];
        return s;
    }
    static int bytes(byte[] b, char[] c, short[] sh) {
        b[0] = (byte) 200; c[0] = (char) 65000; sh[0] = (short) 40000;
        return b[0] + c[0] + sh[0];
    }
    static int bump(int by) { counter += by; total += by; return counter; }
    int mix(int x) { base += x; wide += x; scale *= 1.25; data[x & 7] += base; return base + (int) wide + (int) scale + data[x & 7]; }
    static int pick(int[] a, int[] b, int i) { int[] r = i > 3 ? a : b; return r == null ? -1 : r[i % r.length]; }
    static int same(int[] a, int[] b) { return a == b ? 1 : 0; }
    static int[] copy(int[] a) { int[] r = new int[a.length]; for (int i = 0; i < a.length; i++) r[i] = a[i]; return r; }
    static int get(int[] a, int i) { return a[i]; }
    static int len(int[] a) { return a.length; }
    int field(JitObjTest o) { return o.base; }

    public static void main(String[] args) {
        int acc = 0;
        for (int i = 0; i < 40; i++) acc += sieve(100 + i) + bump(i);
        long[] la = {1, 2, 3, 4}; double[] da = {1.5, 2.5, 3.5, 4.5};
        int[] arr = {9, 3, 7, 1, 8, 2};
        for (int i = 0; i < 30; i++) { acc += (int) dot(la, da); acc += sortAndSum(copy(arr)); }
        byte[] bb = new byte[2]; char[] cc = new char[2]; short[] ss = new short[2];
        for (int i = 0; i < 30; i++) acc += bytes(bb, cc, ss);
        JitObjTest o = new JitObjTest();
        for (int i = 0; i < 30; i++) acc += o.mix(i);
        int[] p = {4, 5, 6};
        for (int i = 0; i < 30; i++) acc += pick(p, null, i % 6 + 1) + same(p, p) + same(p, null) + o.field(o);
        System.out.println(acc + "|" + counter + "|" + total + "|" + o.base + "|" + o.wide + "|" + (long) (o.scale * 1000));
        for (int i = 0; i < 30; i++) { len(p); }
        try { for (int i = 0; i < 30; i++) get(p, i); } catch (ArrayIndexOutOfBoundsException e) { System.out.println("aioobe"); }
        try { for (int i = 0; i < 30; i++) len(i < 29 ? p : null); } catch (NullPointerException e) { System.out.println("npe"); }
        try { for (int i = 0; i < 30; i++) o.field(i < 29 ? o : null); } catch (NullPointerException e) { System.out.println("npe2"); }
    }
}
