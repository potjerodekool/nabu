import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Virtual threads that park on something nobody can reach any more are garbage: their stacks are given back. Like on the
 * JVM that needs jdk.trackAllThreads=false, otherwise the root thread container keeps every virtual thread alive.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.trackAllThreads", "false");
        final int rounds = 40;
        final int perRound = 5000;
        final AtomicInteger parked = new AtomicInteger();
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < perRound; i++) {
                final CountDownLatch never = new CountDownLatch(1);
                Thread.startVirtualThread(new Runnable() {
                    public void run() {
                        parked.incrementAndGet();
                        try {
                            never.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                });
            }
            while (parked.get() < (round + 1) * perRound) {
                Thread.sleep(5);
            }
            System.gc();
        }
        System.out.println("parked " + parked.get());
    }
}
