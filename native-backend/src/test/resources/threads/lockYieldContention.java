import java.util.concurrent.locks.ReentrantLock;

/** 100 virtual threads that fight for a ReentrantLock and yield inside the critical section. */
public class Main {
    public static void main(String[] args) throws Exception {
        final ReentrantLock lock = new ReentrantLock();
        final int[] counter = new int[1];
        Thread[] threads = new Thread[100];
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
