import java.util.ServiceLoader;

/** Phase 3: ServiceLoader finds providers in META-INF/services on the class path. */
public class ServiceMetaTest {
    public interface Greeter { String hello(); }
    public static class En implements Greeter { public String hello() { return "hello meta"; } }

    public static void main(String[] args) throws Exception {
        int n = 0;
        for (Greeter g : ServiceLoader.load(Greeter.class)) { System.out.println(g.hello()); n++; }
        System.out.println(n);
    }
}
