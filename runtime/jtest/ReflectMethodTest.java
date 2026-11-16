import java.lang.reflect.Method;

/** Phase 3: getDeclaredMethods + Method.invoke through the real JDK classes. */
public class ReflectMethodTest {
    public static int answer() { return 42; }

    public static void main(String[] args) throws Exception {
        for (Method m : Class.forName("ReflectMethodTest").getDeclaredMethods()) {
            if (m.getName().equals("answer")) System.out.println(m.invoke(null));
        }
    }
}
