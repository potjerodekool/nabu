/**
 * The harness compiles this class as t0.Main, next to the Main of its dispatcher: two classes with the same simple name
 * in different packages must each get their own constructor.
 */
public class Main {
    final StringBuffer buffer = new StringBuffer();
    final String name = "n";
    int plain = 5;
    int body;

    Main() {
        body = 8;
    }

    public static void main(String[] args) throws Exception {
        final Main m = new Main();
        System.out.println("direct " + m.buffer.length() + " " + m.name + " " + m.plain + " " + m.body);
        Runnable r = new Runnable() {
            public void run() {
                m.buffer.append('x');
                System.out.println("inner " + m.buffer.length());
            }
        };
        r.run();
        Thread t = new Thread(r);
        t.start();
        t.join();
    }
}
