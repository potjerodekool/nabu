import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Phase 3: reflection by name, with parameter types, through the real JDK classes. */
public class ReflectTest {
    public int count = 10;
    public String label = "x";
    public ReflectTest() { }
    public ReflectTest(int count) { this.count = count; }
    public int twice(int x) { return x * 2; }
    public static int answer() { return 42; }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("ReflectTest");
        System.out.println(c.getDeclaredMethod("answer").invoke(null));
        Object o = c.getDeclaredConstructor(int.class).newInstance(7);
        System.out.println(((ReflectTest) o).count);
        System.out.println(c.getDeclaredMethod("twice", int.class).invoke(o, 21));
        Field f = c.getDeclaredField("count");
        System.out.println(f.getName());
        System.out.println(f.get(o));
        f.set(o, 99);
        System.out.println(((ReflectTest) o).count);
        System.out.println(c.getDeclaredField("label").get(o));
    }
}
