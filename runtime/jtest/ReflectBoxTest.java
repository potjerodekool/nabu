import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reflection boxes and unboxes by descriptor: boolean and char fields, boolean and int parameters and results. */
public class ReflectBoxTest {
    public boolean on = true;
    public char letter = 'k';
    public int number = 3;

    public boolean not(boolean b) { return !b; }
    public int plus(int a, int b) { return a + b; }
    public char next(char c) { return (char) (c + 1); }

    public static void main(String[] args) throws Exception {
        ReflectBoxTest o = new ReflectBoxTest();
        Class<?> c = ReflectBoxTest.class;
        Object on = c.getDeclaredField("on").get(o);
        System.out.println(on);
        System.out.println(c.getDeclaredField("letter").get(o));
        System.out.println(c.getDeclaredField("number").get(o));
        Field f = c.getDeclaredField("on");
        f.set(o, Boolean.FALSE);
        System.out.println(o.on);
        System.out.println(c.getDeclaredMethod("not", boolean.class).invoke(o, true));
        System.out.println(c.getDeclaredMethod("plus", int.class, int.class).invoke(o, 2, 3));
        System.out.println(c.getDeclaredMethod("next", char.class).invoke(o, 'a'));
        Boolean t = Boolean.TRUE;
        System.out.println(t);
    }
}
