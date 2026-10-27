import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

public class Main {
    public static void main(String[] args) throws Exception {
        final AtomicInteger counter = new AtomicInteger();
        Thread t = new Thread(() -> counter.addAndGet(3));
        t.start();
        t.join();
        System.out.println("thread " + counter.get());

        final CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> latch.countDown()).start();
        latch.await();
        System.out.println("latch " + latch.getCount());

        ScheduledExecutorService ses = Executors.newScheduledThreadPool(1);
        ScheduledFuture<Integer> f = ses.schedule(() -> 42, 10, TimeUnit.MILLISECONDS);
        System.out.println("scheduled " + f.get());
        ses.shutdown();

        CompletableFuture<Integer> chain = CompletableFuture.supplyAsync(() -> 20).thenApply(x -> x + 1);
        System.out.println("chain " + chain.get());

        UnaryOperator<String> op = new UnaryOperator<String>() {
            public String apply(String s) {
                return s + "!";
            }
        };
        System.out.println("mapped " + Optional.of("b").map(op).get());
    }
}
