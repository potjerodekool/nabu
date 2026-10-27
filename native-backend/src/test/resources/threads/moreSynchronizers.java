import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

public class Main {
    static int shared;

    public static void main(String[] args) throws Exception {
        final AtomicInteger arrived = new AtomicInteger();
        final CyclicBarrier barrier = new CyclicBarrier(3, () -> arrived.set(3));
        Thread[] ts = new Thread[2];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                }
            });
            ts[i].start();
        }
        barrier.await();
        for (int i = 0; i < ts.length; i++) {
            ts[i].join();
        }
        System.out.println("barrier " + arrived.get());

        final Semaphore semaphore = new Semaphore(2);
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                }
            });
            ts[i].start();
        }
        for (int i = 0; i < ts.length; i++) {
            ts[i].join();
        }
        System.out.println("semaphore " + semaphore.availablePermits());

        final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.writeLock().lock();
        shared = 5;
        rw.writeLock().unlock();
        rw.readLock().lock();
        System.out.println("rw " + shared);
        rw.readLock().unlock();

        final StampedLock stamped = new StampedLock();
        long stamp = stamped.writeLock();
        shared = 7;
        stamped.unlockWrite(stamp);
        long read = stamped.tryOptimisticRead();
        int value = shared;
        System.out.println("stamped " + (stamped.validate(read) ? value : -1));

        final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<Integer, Integer>();
        Thread[] ws = new Thread[3];
        for (int i = 0; i < ws.length; i++) {
            final int base = i * 100;
            ws[i] = new Thread(() -> {
                for (int n = 0; n < 100; n++) {
                    map.put(base + n, n);
                }
            });
            ws[i].start();
        }
        for (int i = 0; i < ws.length; i++) {
            ws[i].join();
        }
        System.out.println("map " + map.size());

        final LinkedTransferQueue<String> transfer = new LinkedTransferQueue<String>();
        Thread sender = new Thread(() -> {
            try {
                transfer.transfer("x");
            } catch (InterruptedException e) {
            }
        });
        sender.start();
        System.out.println("transfer " + transfer.take());
        sender.join();

        int r = ThreadLocalRandom.current().nextInt(10);
        System.out.println("random " + (r >= 0 && r < 10));
    }
}
