import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("first " + System.setProperty("nabu.test.key", "one"));
        System.out.println("second " + System.setProperty("nabu.test.key", "two"));
        System.out.println("get " + System.getProperty("nabu.test.key") + " " + System.getProperty("nabu.test.none", "dflt"));
        System.out.println("properties " + System.getProperties().getProperty("nabu.test.key"));
        try {
            System.setProperty("", "x");
        } catch (IllegalArgumentException e) {
            System.out.println("empty IAE");
        }
        try {
            System.setProperty("nabu.test.null", null);
        } catch (NullPointerException e) {
            System.out.println("null NPE");
        }

        // the scheduler of the virtual threads reads its size when the first one is created
        System.setProperty("jdk.virtualThreadScheduler.parallelism", "4");
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "4");
        final AtomicInteger now = new AtomicInteger();
        final AtomicInteger max = new AtomicInteger();
        Thread[] threads = new Thread[32];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    int n = now.incrementAndGet();
                    max.accumulateAndGet(n, Math::max);
                    long x = 1;
                    for (int k = 0; k < 20000000; k++) {
                        x = x * 31 + k;
                    }
                    if (x == 42) System.out.println();
                    now.decrementAndGet();
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("parallel " + (max.get() > 1 && max.get() <= 4));
    }
}
