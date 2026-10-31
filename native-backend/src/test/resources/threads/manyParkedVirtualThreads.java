import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Many virtual threads that are all parked at the same time: what a stack of a virtual thread costs. */
public class Main {
    public static void main(String[] args) throws Exception {
        final int count = System.getenv("PARKED") == null ? 20000 : Integer.parseInt(System.getenv("PARKED"));
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(count);
        final AtomicInteger finished = new AtomicInteger();
        for (int i = 0; i < count; i++) {
            Thread.startVirtualThread(new Runnable() {
                public void run() {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    finished.incrementAndGet();
                }
            });
        }
        started.await();
        System.out.println("parked " + count);
        release.countDown();
        for (int i = 0; i < 6000 && finished.get() < count; i++) {
            Thread.sleep(10);
        }
        System.out.println("finished " + finished.get());
    }
}
