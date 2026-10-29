import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class Main {
    static final Object lock = new Object();
    static boolean notified;

    public static void main(String[] args) throws Exception {
        manySleepers();
        interruptedSleeper();
        monitorWait();
        pinnedSleep();
        joinPlatformThread();
        contendedLock();
    }

    /** Sleeping virtual threads do not hold on to a carrier: thousands of them sleep at the same time. */
    static void manySleepers() throws Exception {
        final AtomicInteger woke = new AtomicInteger();
        final long start = System.currentTimeMillis();
        Thread[] threads = new Thread[2000];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().unstarted(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(300);
                        woke.incrementAndGet();
                    } catch (InterruptedException e) {
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        final long took = System.currentTimeMillis() - start;
        System.out.println("sleepers " + woke.get() + " fast " + (took < 8000));
    }

    static void interruptedSleeper() throws Exception {
        final boolean[] result = new boolean[2];
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(20000);
                } catch (InterruptedException e) {
                    result[0] = true;
                    result[1] = Thread.currentThread().isInterrupted();
                }
            }
        });
        Thread.sleep(200);
        t.interrupt();
        t.join();
        System.out.println("interrupt " + result[0] + " flagCleared " + !result[1]);
    }

    /** Object.wait in a virtual thread keeps its carrier (it is pinned); another virtual thread notifies. */
    static void monitorWait() throws Exception {
        final boolean[] done = new boolean[1];
        Thread waiter = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                synchronized (lock) {
                    while (!notified) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    done[0] = true;
                }
            }
        });
        Thread.sleep(100);
        Thread notifier = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                synchronized (lock) {
                    notified = true;
                    lock.notifyAll();
                }
            }
        });
        waiter.join();
        notifier.join();
        System.out.println("monitorWait " + done[0]);
    }

    /** Sleeping while holding a monitor cannot unmount; it has to work all the same. */
    static void pinnedSleep() throws Exception {
        final Object own = new Object();
        final long[] slept = new long[1];
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                synchronized (own) {
                    long s = System.currentTimeMillis();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                    }
                    slept[0] = System.currentTimeMillis() - s;
                }
            }
        });
        t.join();
        System.out.println("pinnedSleep " + (slept[0] >= 80));
    }

    static void joinPlatformThread() throws Exception {
        final int[] value = new int[1];
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                Thread platform = new Thread(new Runnable() {
                    public void run() {
                        value[0] = 42;
                    }
                });
                platform.start();
                try {
                    platform.join();
                } catch (InterruptedException e) {
                }
            }
        });
        t.join();
        System.out.println("joinPlatform " + value[0]);
    }

    static void contendedLock() throws Exception {
        final ReentrantLock reentrant = new ReentrantLock();
        final int[] counter = new int[1];
        Thread[] threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    for (int k = 0; k < 10; k++) {
                        reentrant.lock();
                        try {
                            counter[0]++;
                            Thread.yield();
                        } finally {
                            reentrant.unlock();
                        }
                    }
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("reentrant " + counter[0]);
    }
}
