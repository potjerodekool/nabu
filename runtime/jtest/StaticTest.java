/** Static fields, static initializers and the primitive class literals. */
public class StaticTest {
    static int counter = 5;
    static final String NAME;
    static { NAME = "static-" + counter; }

    static int next() { return ++counter; }

    public static void main(String[] args) {
        System.out.println(NAME);
        System.out.println(next());
        System.out.println(next());
        System.out.println(counter);
        System.out.println(int.class.getName());
        System.out.println(counter > 6);
    }
}
