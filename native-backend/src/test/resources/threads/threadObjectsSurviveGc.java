import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Objects that only a thread, a fiber or a callback refers to must survive a collection: a thread that was just made
 * is not yet known to the collector, a pool forgets its executors, and an exception that is on its way holds on to
 * its message only through the unwinder. Everything here is started and dropped without keeping a reference, while the
 * collector runs.
 */
public class Main {
    static final AtomicInteger platform = new AtomicInteger();
    static final AtomicInteger virtual = new AtomicInteger();
    static final AtomicInteger pooled = new AtomicInteger();
    static final AtomicInteger async = new AtomicInteger();
    static final AtomicInteger flights = new AtomicInteger();
    static long sink;

    /** Garbage, so that the collector has work to do. */
    static void churn(int rounds) {
        for (int i = 0; i < rounds; i++) {
            byte[][] junk = new byte[32][];
            for (int j = 0; j < junk.length; j++) {
                junk[j] = new byte[16 * 1024];
            }
            sink += junk[i % 32].length;
        }
    }

    static void await(AtomicInteger counter, int expected) throws Exception {
        for (int i = 0; i < 6000 && counter.get() < expected; i++) {
            Thread.sleep(10);
        }
    }

    static void platformThreads() throws Exception {
        for (int i = 0; i < 200; i++) {
            final String name = "worker-" + i;
            new Thread(new Runnable() {
                public void run() {
                    churn(2);
                    if (name.startsWith("worker-")) {
                        platform.incrementAndGet();
                    }
                }
            }, name).start();
            if (i % 4 == 0) {
                System.gc();
            }
            churn(3);
        }
        await(platform, 200);
        System.out.println("platform " + platform.get());
    }

    static void virtualThreads() throws Exception {
        for (int i = 0; i < 200; i++) {
            final String name = "vthread-" + i;
            Thread.startVirtualThread(new Runnable() {
                public void run() {
                    churn(2);
                    if (name.startsWith("vthread-")) {
                        virtual.incrementAndGet();
                    }
                }
            });
            if (i % 4 == 0) {
                System.gc();
            }
            churn(3);
        }
        await(virtual, 200);
        System.out.println("virtual " + virtual.get());
    }

    static void pools() throws Exception {
        for (int round = 0; round < 20; round++) {
            ExecutorService pool = Executors.newFixedThreadPool(3);
            for (int i = 0; i < 10; i++) {
                pool.submit(new Runnable() {
                    public void run() {
                        churn(1);
                        pooled.incrementAndGet();
                    }
                });
            }
            pool.shutdown();
            pool = null;
            System.gc();
            churn(3);
        }
        await(pooled, 200);
        System.out.println("pooled " + pooled.get());
        for (int i = 0; i < 100; i++) {
            CompletableFuture.runAsync(new Runnable() {
                public void run() {
                    churn(1);
                    async.incrementAndGet();
                }
            });
            if (i % 10 == 0) {
                System.gc();
            }
        }
        await(async, 100);
        System.out.println("async " + async.get());
    }

    /** The message of an exception that is on its way is only reachable through the unwinder while a finally block runs. */
    static void flight(int id) {
        try {
            try {
                throw new IllegalStateException("in flight " + id);
            } finally {
                churn(10);
                System.gc();
                churn(10);
            }
        } catch (IllegalStateException e) {
            if (("in flight " + id).equals(e.getMessage())) {
                flights.incrementAndGet();
            }
        }
    }

    static void exceptionsInFlight() throws Exception {
        for (int i = 0; i < 20; i++) {
            flight(i);
        }
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int base = 100 * (t + 1);
            threads[t] = new Thread(new Runnable() {
                public void run() {
                    for (int i = 0; i < 20; i++) {
                        flight(base + i);
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        System.out.println("flights " + flights.get());
    }

    public static void main(String[] args) throws Exception {
        platformThreads();
        virtualThreads();
        pools();
        exceptionsInFlight();
        System.out.println("sink " + (sink > 0));
    }
}
