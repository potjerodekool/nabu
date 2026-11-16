import java.util.ServiceLoader;

/** A class and a service provider that are only in a jar on NABU_CLASSPATH: loaded from the zip entries. */
public class JarLoadTest {
    public static void main(String[] args) throws Exception {
        Class<?> math = Class.forName("JarMath");
        System.out.println(math.getMethod("twice", int.class).invoke(null, 21));
        for (JarService service : ServiceLoader.load(JarService.class)) {
            System.out.println(service.name());
        }
    }
}
