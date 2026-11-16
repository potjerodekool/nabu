/** pop2, dup2, dup_x2, dup2_x1 and dup2_x2 on long and double values and on pairs of ints. */
public class WideStackTest {
    static long sl = 10;
    static double sd = 1.5;
    long fl = 100;
    double fd = 2.5;
    long[] la = new long[3];
    double[] da = new double[3];
    int[] ia = new int[3];

    long postIncStatic() { return sl++; }
    long preIncStatic() { return ++sl; }
    long postIncField() { return fl++; }
    long preIncField() { return ++fl; }
    double postIncDouble() { return fd++; }
    long arrayCompound(int i) { la[i] += 7; return la[i]; }
    long arrayPost(int i) { return la[i]++; }
    double arrayDoubleCompound(int i) { da[i] += 1.25; return da[i] * 2; }
    int intArrayPre(int i) { return ++ia[i]; }
    int intArrayPost(int i) { return ia[i]++; }
    long chained() { long a, b; a = b = 5L; return a + b; }
    long chainedField() { long a; a = fl = 9L; return a + fl; }
    void discarded() { long x = 3; x++; double d = 2.0; d++; sl += 0; fl++; }  // pop2 of a long result
    long ternary(boolean b) { return b ? sl : fl; }
    int swapInts(int a, int b) { int t = a; a = b; b = t; return a * 10 + b; }

    public static void main(String[] args) {
        WideStackTest t = new WideStackTest();
        System.out.println(t.postIncStatic() + "|" + t.preIncStatic() + "|" + sl);
        System.out.println(t.postIncField() + "|" + t.preIncField() + "|" + t.fl);
        System.out.println(t.postIncDouble() + "|" + t.fd);
        System.out.println(t.arrayCompound(1) + "|" + t.arrayCompound(1) + "|" + t.arrayPost(2) + "|" + t.la[2]);
        System.out.println(t.arrayDoubleCompound(0) + "|" + t.arrayDoubleCompound(0));
        System.out.println(t.intArrayPre(1) + "|" + t.intArrayPost(1) + "|" + t.ia[1]);
        System.out.println(t.chained() + "|" + t.chainedField() + "|" + t.fl);
        t.discarded();
        System.out.println(sl + "|" + t.fl + "|" + t.ternary(true) + "|" + t.ternary(false) + "|" + t.swapInts(3, 4));
    }
}
