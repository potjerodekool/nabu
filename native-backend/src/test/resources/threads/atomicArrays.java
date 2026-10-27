import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class Main {
    public static void main(String[] args) throws Exception {
        final AtomicIntegerArray ints = new AtomicIntegerArray(4);
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int n = 0; n < 1000; n++) {
                    ints.incrementAndGet(1);
                }
            });
            threads[i].start();
        }
        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }
        ints.set(2, 3);
        System.out.println("ints " + ints.get(1) + " " + ints.get(2));

        AtomicReferenceArray<String> refs = new AtomicReferenceArray<String>(2);
        refs.set(0, "a");
        refs.compareAndSet(0, "a", "b");
        refs.getAndSet(1, "c");
        System.out.println("refs " + refs.get(0) + " " + refs.get(1));

        AtomicLongArray longs = new AtomicLongArray(1);
        longs.addAndGet(0, 2L);
        System.out.println("long " + longs.get(0));
    }
}
