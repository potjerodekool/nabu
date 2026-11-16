/** invokevirtual (overrides, a polymorphic call site), invokespecial (super, private) from compiled code. */
public class JitVirtTest {
    static abstract class Shape {
        int w = 2;
        abstract int area();
        int weighted(int k) { return area() * k + bonus(); }
        int bonus() { return 1; }
        private int hidden(int x) { return x * 3; }
        int viaPrivate(int x) { return hidden(x) + w; }
    }
    static class Sq extends Shape { int area() { return w * w; } }
    static class Rect extends Shape {
        int h = 5;
        int area() { return w * h; }
        int bonus() { return super.bonus() + 10; }
    }
    static class Big extends Rect { int area() { return super.area() * 2; } }

    static int total(Shape[] shapes, int rounds) {
        int s = 0;
        for (int r = 0; r < rounds; r++)
            for (int i = 0; i < shapes.length; i++) s += shapes[i].weighted(r) + shapes[i].viaPrivate(r);
        return s;
    }
    static int grow(Rect r, int n) { for (int i = 0; i < n; i++) r.h += r.area() & 3; return r.h; }

    public static void main(String[] args) {
        Shape[] shapes = { new Sq(), new Rect(), new Big(), new Sq() };
        System.out.println(total(shapes, 50));
        System.out.println(grow(new Big(), 100) + "|" + grow(new Rect(), 100));
    }
}
