import java.util.concurrent.atomic.AtomicInteger;

/** 100 virtual threads that yield again and again, with no lock. */
public class Main {
    public static void main(String[] args) throws Exception {
        final AtomicInteger counter = new AtomicInteger();
        Thread[] threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    for (int k = 0; k < 10; k++) {
                        counter.incrementAndGet();
                        Thread.yield();
                    }
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("counter " + counter.get());
    }
}
