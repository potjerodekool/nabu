import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/** Phase 3: a lambda as InvocationHandler. */
public class ProxyLambdaTest {
    interface Greeter { String greet(String n); }

    public static void main(String[] args) throws Exception {
        String prefix = "hi ";
        InvocationHandler h = (proxy, method, a) -> prefix + a[0] + " via " + method.getName();
        Greeter g = (Greeter) Proxy.newProxyInstance(ProxyLambdaTest.class.getClassLoader(), new Class<?>[] { Greeter.class }, h);
        System.out.println(g.greet("bob"));
        System.out.println(Proxy.isProxyClass(g.getClass()));
        System.out.println(Proxy.isProxyClass(String.class));
    }
}
