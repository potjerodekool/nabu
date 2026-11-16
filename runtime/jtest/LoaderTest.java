public class LoaderTest {
    public static void main(String[] args) throws Exception {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        System.out.println(cl != null ? "loader" : "null");
        Class<?> c = cl.loadClass("java.lang.String");
        System.out.println(c.getName());
    }
}
