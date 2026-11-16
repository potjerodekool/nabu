/** A polymorphic loop with virtual calls and fields, called once: time with and without NABU_JIT=0. */
public class JitVirtBench {
    static abstract class Shape { int w = 2; abstract int area(); int weighted(int k) { return area() * k + 1; } }
    static class Sq extends Shape { int area() { return w * w; } }
    static class Rect extends Shape { int h = 5; int area() { return w * h; } }
    static int total(Shape[] shapes, int rounds) {
        int s = 0;
        for (int r = 0; r < rounds; r++) for (int i = 0; i < shapes.length; i++) s += shapes[i].weighted(r & 7);
        return s;
    }
    public static void main(String[] args) {
        System.out.println(total(new Shape[] { new Sq(), new Rect(), new Sq(), new Rect() }, 200000));
    }
}
