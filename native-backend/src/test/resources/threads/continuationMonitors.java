import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class Main {
    static final Object lock = new Object();
    static boolean go;

    public static void main(String[] args) throws Exception {
        waitersBeyondCarriers();
        synchronizedSleepers();
        waitTimeout();
        interruptedWaiter();
        notifyOne();
        parkInsideMonitor();
        waitWithoutMonitor();
        syncExits();
        contendedHandoff();
    }

    /** More waiting virtual threads than a pool could keep as pinned carriers: wait lets go of the carrier. */
    static void waitersBeyondCarriers() throws Exception {
        final AtomicInteger done = new AtomicInteger();
        Thread[] threads = new Thread[600];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    synchronized (lock) {
                        while (!go) {
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                    done.incrementAndGet();
                }
            });
        }
        Thread.sleep(300);
        Thread notifier = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                synchronized (lock) {
                    go = true;
                    lock.notifyAll();
                }
            }
        });
        notifier.join();
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("waiters " + done.get());
    }

    /** The owner of a monitor sleeps (unmounted) while many others wait for the monitor. */
    static void synchronizedSleepers() throws Exception {
        final Object own = new Object();
        final int[] counter = new int[1];
        Thread[] threads = new Thread[300];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    synchronized (own) {
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException e) {
                        }
                        counter[0]++;
                    }
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("sleepers " + counter[0]);
    }

    static void waitTimeout() throws Exception {
        final long[] took = new long[1];
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                Object own = new Object();
                synchronized (own) {
                    long s = System.currentTimeMillis();
                    try {
                        own.wait(150);
                    } catch (InterruptedException e) {
                    }
                    took[0] = System.currentTimeMillis() - s;
                }
            }
        });
        t.join();
        System.out.println("timeout " + (took[0] >= 120 && took[0] < 5000));
    }

    static void interruptedWaiter() throws Exception {
        final boolean[] result = new boolean[2];
        final boolean[] waiting = new boolean[1];
        final Object own = new Object();
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                synchronized (own) {
                    waiting[0] = true;
                    try {
                        own.wait();
                    } catch (InterruptedException e) {
                        result[0] = true;
                        result[1] = Thread.currentThread().isInterrupted();
                    }
                }
            }
        });
        Thread.sleep(200);
        t.interrupt();
        t.join();
        System.out.println("interrupt " + waiting[0] + " " + result[0] + " flagCleared " + !result[1]);
    }

    /** notify wakes exactly one waiter. */
    static void notifyOne() throws Exception {
        final Object own = new Object();
        final AtomicInteger woken = new AtomicInteger();
        final int[] ready = new int[1];
        Thread[] threads = new Thread[3];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    synchronized (own) {
                        ready[0]++;
                        try {
                            own.wait();
                            woken.incrementAndGet();
                        } catch (InterruptedException e) {
                        }
                    }
                }
            });
        }
        boolean all = false;
        while (!all) {
            synchronized (own) {
                all = ready[0] == 3;
            }
            Thread.sleep(10);
        }
        synchronized (own) {
            own.notify();
        }
        Thread.sleep(200);
        int afterOne = woken.get();
        synchronized (own) {
            own.notify();
            own.notify();
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("notify " + afterOne + " " + woken.get());
    }

    /** A virtual thread that parks inside synchronized keeps the monitor; the others wait for it. */
    static void parkInsideMonitor() throws Exception {
        final Object own = new Object();
        final StringBuffer order = new StringBuffer();
        final boolean[] holding = new boolean[1];
        Thread a = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                synchronized (own) {
                    holding[0] = true;
                    LockSupport.park();
                    order.append("A");
                }
            }
        });
        while (!holding[0]) {
            Thread.sleep(10);
        }
        Thread b = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                synchronized (own) {
                    order.append("B");
                }
            }
        });
        Thread.sleep(200);
        order.append("-");
        LockSupport.unpark(a);
        a.join();
        b.join();
        System.out.println("park " + order);
    }

    static void waitWithoutMonitor() throws Exception {
        final String[] result = new String[1];
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                try {
                    new Object().wait();
                } catch (IllegalMonitorStateException e) {
                    result[0] = "IMSE";
                } catch (InterruptedException e) {
                    result[0] = "interrupted";
                }
            }
        });
        t.join();
        System.out.println("noMonitor " + result[0]);
    }

    static int early(Object o) {
        synchronized (o) {
            return 7;
        }
    }

    /** break, continue, return and an exception leave a synchronized block and release the monitor. */
    static void syncExits() throws Exception {
        final Object own = new Object();
        int n = 0;
        for (int i = 0; i < 5; i++) {
            synchronized (own) {
                if (i == 1) continue;
                if (i == 3) break;
                n++;
            }
        }
        int r = early(own);
        String ex = "none";
        try {
            synchronized (own) {
                throw new IllegalStateException("x");
            }
        } catch (IllegalStateException e) {
            ex = "ISE";
        }
        final boolean[] other = new boolean[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                synchronized (own) {
                    other[0] = true;
                }
            }
        });
        t.start();
        t.join();
        System.out.println("syncExits " + n + " " + r + " " + ex + " " + other[0]);
    }

    /** Many virtual threads fight for one monitor; each release hands it over to a queued thread. */
    static void contendedHandoff() throws Exception {
        final Object own = new Object();
        final int[] counter = new int[1];
        Thread[] threads = new Thread[200];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    for (int k = 0; k < 100; k++) {
                        synchronized (own) {
                            counter[0]++;
                            if (k % 10 == 0) {
                                Thread.yield();
                            }
                        }
                    }
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("handoff " + counter[0]);
    }
}
