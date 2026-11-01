/** The carrier threads of the virtual threads end when they have been idle for 30 seconds; later virtual threads get new ones. */
public class Main {
    static void runOne(String name) throws Exception {
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                Thread.yield();
            }
        });
        t.join();
        System.out.println(name);
    }

    public static void main(String[] args) throws Exception {
        runOne("first");
        Thread.sleep(35000);
        runOne("second");
        Thread[] threads = new Thread[50];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    Thread.yield();
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("many");
    }
}
