/** Overriding, super calls, interfaces and abstract methods in interpreted code. */
public class VirtualTest {
    interface Shape { int area(); }
    static abstract class Base implements Shape {
        int w = 2;
        abstract int factor();
        public int area() { return w * factor(); }
        int twice() { return area() * 2; }
    }
    static class Sq extends Base { int factor() { return 3; } }
    static class Big extends Sq { int factor() { return super.factor() + 10; } public int area() { return super.area() + 1; } }
    public static void main(String[] args) {
        Base a = new Sq(); Base b = new Big(); Shape s = b;
        System.out.println(a.area());
        System.out.println(b.area());
        System.out.println(b.twice());
        System.out.println(s.area());
    }
}
