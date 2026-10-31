import java.util.Vector;

/** A synchronized method holds the monitor of this (or of the class) while it runs: nothing is lost, wait works. */
public class Main {
    static int staticCount;
    int count;
    static final StringBuffer buffer = new StringBuffer();
    static final Vector<Integer> vector = new Vector<Integer>();

    synchronized void increment() {
        int c = count;
        Thread.yield();
        count = c + 1;
    }

    static synchronized void incrementStatic() {
        int c = staticCount;
        Thread.yield();
        staticCount = c + 1;
    }

    boolean ready;

    synchronized void await() throws InterruptedException {
        while (!ready) {
            wait();
        }
    }

    synchronized void signal() {
        ready = true;
        notifyAll();
    }

    public static void main(String[] args) throws Exception {
        final Main m = new Main();
        final Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(new Runnable() {
                public void run() {
                    for (int i = 0; i < 2000; i++) {
                        m.increment();
                        incrementStatic();
                        buffer.append('x');
                        vector.add(i);
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        System.out.println("count " + m.count);
        System.out.println("static " + staticCount);
        System.out.println("buffer " + buffer.length());
        System.out.println("vector " + vector.size());

        final Thread waiter = new Thread(new Runnable() {
            public void run() {
                try {
                    m.await();
                    System.out.println("woken");
                } catch (InterruptedException e) {
                    System.out.println("interrupted");
                }
            }
        });
        waiter.start();
        Thread.sleep(200);
        m.signal();
        waiter.join();
    }
}
