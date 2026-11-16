import java.lang.reflect.Field;

/** getMethod and getField find what a class inherits from its superclasses and interfaces; getDeclared* do not. */
public class ReflectInheritTest {
    interface Named { String name(); }
    static class Base { public int id = 11; public int twice(int x) { return x * 2; } }
    static class Derived extends Base implements Named { public String name() { return "derived"; } }

    public static void main(String[] args) throws Exception {
        Derived d = new Derived();
        Class<?> c = Derived.class;
        System.out.println(c.getMethod("twice", int.class).invoke(d, 4));
        System.out.println(c.getMethod("name").invoke(d));
        Field f = c.getField("id");
        System.out.println(f.get(d));
        try {
            c.getDeclaredMethod("twice", int.class);
            System.out.println("found");
        } catch (NoSuchMethodException e) {
            System.out.println("not declared");
        }
        try {
            c.getMethod("nope");
            System.out.println("found");
        } catch (NoSuchMethodException e) {
            System.out.println("no such method");
        }
    }
}
