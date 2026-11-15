import java.util.ServiceLoader;
public class ServiceTest {
    public interface Greeter { String hello(); }
    public static class En implements Greeter { public String hello() { return "hello"; } }
    public static void main(String[] args) throws Exception {
        int n = 0;
        for (Greeter g : ServiceLoader.load(Greeter.class)) { System.out.println(g.hello()); n++; }
        System.out.println(n);
    }
}
