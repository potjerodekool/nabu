public class ObjTest {
    int x; String name;
    ObjTest(int x, String name) { this.x = x; this.name = name; }
    int get() { return x; }
    public static void main(String[] args) {
        ObjTest o = new ObjTest(42, "hello");
        System.out.println(o.get());
        System.out.println(o.name);
        o.x = 99;
        System.out.println(o.x);
    }
}
