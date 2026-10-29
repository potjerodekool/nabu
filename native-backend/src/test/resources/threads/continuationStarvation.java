import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    public static void main(String[] args) throws Exception {
        pinnedFlood();
        pinnedThenRelease();
    }

    /** Far more virtual threads sleep inside a monitor (pinned) than there are carriers; all of them still finish. */
    static void pinnedFlood() throws Exception {
        final AtomicInteger done = new AtomicInteger();
        Thread[] threads = new Thread[64];
        for (int i = 0; i < threads.length; i++) {
            final Object own = new Object();
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    synchronized (own) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                        }
                        done.incrementAndGet();
                    }
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("pinnedFlood " + done.get());
    }

    /** Pinned threads occupy carriers while unpinned ones queue up; once the pinned ones leave, the queue drains. */
    static void pinnedThenRelease() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger pinnedDone = new AtomicInteger();
        final AtomicInteger freeDone = new AtomicInteger();
        Thread[] threads = new Thread[40];
        for (int i = 0; i < 20; i++) {
            final Object own = new Object();
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    synchronized (own) {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                        }
                        pinnedDone.incrementAndGet();
                    }
                }
            });
        }
        for (int i = 20; i < 40; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    Thread.yield();
                    freeDone.incrementAndGet();
                }
            });
        }
        Thread.sleep(200);
        release.countDown();
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("pinnedThenRelease " + pinnedDone.get() + " " + freeDone.get());
    }
}
