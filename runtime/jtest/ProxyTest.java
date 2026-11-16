import java.lang.reflect.*;
public class ProxyTest {
    interface Greeter { String greet(String n); }
    static class H implements InvocationHandler {
        public Object invoke(Object proxy, Method method, Object[] a) { return "hello " + a[0] + " via " + method.getName(); }
    }
    public static void main(String[] args) throws Exception {
        Greeter g = (Greeter) Proxy.newProxyInstance(ProxyTest.class.getClassLoader(), new Class<?>[] { Greeter.class }, new H());
        System.out.println(g.greet("bob"));
    }
}
