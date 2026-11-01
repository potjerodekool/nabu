import java.util.concurrent.locks.ReentrantLock;

/** 100 virtual threads that fight for a ReentrantLock and yield inside the critical section. */
public class Main {
    /** A lock that shows its owner and its queue. */
    static class Lock extends ReentrantLock {
        Thread owner() {
            return getOwner();
        }

        java.util.Collection<Thread> queued() {
            return getQueuedThreads();
        }
    }

    public static void main(String[] args) throws Exception {
        final Lock lock = new Lock();
        final int[] counter = new int[1];
        final Thread[] threads = new Thread[100];
        // Only does something when the program hangs: says what the owner and the queue of the lock are doing.
        final Thread watchdog = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    return;
                }
                final Thread owner = lock.owner();
                System.err.println("HANG counter=" + counter[0] + " locked=" + lock.isLocked()
                        + " owner=" + (owner == null ? "none" : owner.getState().toString())
                        + " queued=" + lock.getQueueLength());
                int runnable = 0;
                int waiting = 0;
                int other = 0;
                for (Thread t : lock.queued()) {
                    Thread.State s = t.getState();
                    if (s == Thread.State.RUNNABLE) runnable++; else if (s == Thread.State.WAITING) waiting++; else other++;
                }
                System.err.println("HANG queued runnable=" + runnable + " waiting=" + waiting + " other=" + other);
                int[] states = new int[8];
                for (Thread t : threads) {
                    if (t != null) states[t.getState().ordinal()]++;
                }
                System.err.println("HANG all threads by state NEW,RUNNABLE,BLOCKED,WAITING,TIMED_WAITING,TERMINATED = "
                        + states[0] + "," + states[1] + "," + states[2] + "," + states[3] + "," + states[4] + "," + states[5]);
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    for (int k = 0; k < 10; k++) {
                        lock.lock();
                        try {
                            counter[0]++;
                            Thread.yield();
                        } finally {
                            lock.unlock();
                        }
                    }
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("counter " + counter[0]);
    }
}
