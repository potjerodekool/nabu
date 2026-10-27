import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Main {
    static int counter;

    public static void main(String[] args) throws Exception {
        final ReentrantLock lock = new ReentrantLock();
        Thread[] threads = new Thread[2];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(new Runnable() {
                public void run() {
                    for (int i = 0; i < 1000; i++) {
                        lock.lock();
                        try {
                            counter++;
                        } finally {
                            lock.unlock();
                        }
                    }
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("lock " + counter);

        final CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            new Thread(new Runnable() {
                public void run() {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();
        System.out.println("latch " + latch.getCount());

        final Semaphore semaphore = new Semaphore(0);
        for (int i = 0; i < 3; i++) {
            new Thread(new Runnable() {
                public void run() {
                    semaphore.release();
                }
            }).start();
        }
        semaphore.acquire(3);
        System.out.println("semaphore 3");

        final CyclicBarrier barrier = new CyclicBarrier(3);
        final AtomicInteger passed = new AtomicInteger();
        Thread[] b = new Thread[2];
        for (int i = 0; i < 2; i++) {
            b[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        barrier.await();
                        passed.incrementAndGet();
                    } catch (Exception e) {
                    }
                }
            });
            b[i].start();
        }
        barrier.await();
        for (Thread t : b) {
            t.join();
        }
        System.out.println("barrier " + (passed.get() + 1));

        final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.writeLock().lock();
        counter = 5;
        rw.writeLock().unlock();
        rw.readLock().lock();
        System.out.println("readwrite " + counter);
        rw.readLock().unlock();
    }
}
