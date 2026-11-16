import java.util.function.IntBinaryOperator;

/** new and constructors, interfaces (a class, a lambda), string constants, JDK calls and athrow in compiled code. */
public class JitNewTest {
    interface Op { int apply(int a, int b); }
    static class Add implements Op { public int apply(int a, int b) { return a + b; } }
    static class Mul implements Op { public int apply(int a, int b) { return a * b; } }
    static class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        Point plus(Point o) { return new Point(x + o.x, y + o.y); }
        int dist() { return Math.abs(x) + Math.abs(y); }
    }
    static class Counter {
        int n;
        Counter() { this(5); }
        Counter(int start) { n = start; }
    }

    static int fold(Op op, int n) { int acc = 1; for (int i = 1; i <= n; i++) acc = op.apply(acc, i) & 0xffff; return acc; }
    static int walk(int n) {
        Point p = new Point(0, 0);
        for (int i = 0; i < n; i++) p = p.plus(new Point(i, -i * 2));
        return p.dist();
    }
    static int counters(int n) { int s = 0; for (int i = 0; i < n; i++) s += new Counter().n + new Counter(i).n; return s; }
    static int lambdas(IntBinaryOperator f, int n) { int s = 0; for (int i = 0; i < n; i++) s += f.applyAsInt(i, 3); return s; }
    static int jdk(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) s += Math.max(i, 10) + Integer.parseInt("12") + "abc".length();
        return s;
    }
    static int check(int v) { if (v > 40) throw new IllegalStateException("too big"); return v * 2; }

    public static void main(String[] args) {
        System.out.println(fold(new Add(), 50) + "|" + fold(new Mul(), 50));
        System.out.println(walk(60));
        System.out.println(counters(60));
        System.out.println(lambdas((a, b) -> a * b + 1, 60) + "|" + lambdas(Math::max, 60));
        System.out.println(jdk(60));
        int t = 0;
        try { for (int i = 0; i < 60; i++) t += check(i); } catch (IllegalStateException e) { System.out.println("ise " + t); }
    }
}
