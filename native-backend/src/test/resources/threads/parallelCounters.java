import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class Main {
    static int syncCount;
    static final Object lock = new Object();
    static final AtomicInteger atomic = new AtomicInteger();
    static final AtomicLong atomicLong = new AtomicLong();
    static int lockCount;
    static final ReentrantLock reentrant = new ReentrantLock();
    static final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<Integer, Integer>();

    public static void main(String[] args) throws Exception {
        final int threads = 4;
        final int rounds = 20000;
        Thread[] all = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            all[i] = new Thread(new Runnable() {
                public void run() {
                    for (int k = 0; k < rounds; k++) {
                        synchronized (lock) {
                            syncCount++;
                        }
                        atomic.incrementAndGet();
                        atomicLong.addAndGet(2);
                        reentrant.lock();
                        try {
                            lockCount++;
                        } finally {
                            reentrant.unlock();
                        }
                        map.merge(k % 16, 1, (a, b) -> a + b);
                    }
                }
            });
        }
        for (Thread t : all) {
            t.start();
        }
        for (Thread t : all) {
            t.join();
        }
        int mapSum = 0;
        for (int v : map.values()) {
            mapSum += v;
        }
        System.out.println("sync " + syncCount);
        System.out.println("atomic " + atomic.get());
        System.out.println("long " + atomicLong.get());
        System.out.println("lock " + lockCount);
        System.out.println("map " + mapSum);
    }
}
