import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

public class Main {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService ses = Executors.newScheduledThreadPool(2);
        ScheduledFuture<Integer> f = ses.schedule(new Callable<Integer>() {
            public Integer call() {
                return 42;
            }
        }, 50, TimeUnit.MILLISECONDS);
        System.out.println("scheduled " + f.get());
        final AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> repeating = ses.scheduleAtFixedRate(new Runnable() {
            public void run() {
                runs.incrementAndGet();
            }
        }, 0, 20, TimeUnit.MILLISECONDS);
        while (runs.get() < 3) {
            Thread.sleep(10);
        }
        repeating.cancel(false);
        System.out.println("fixed rate ran");
        ses.shutdown();
        ses.awaitTermination(5, TimeUnit.SECONDS);

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(new Supplier<Integer>() {
            public Integer get() {
                return 20;
            }
        });
        CompletableFuture<Integer> chain = first.thenApply(new Function<Integer, Integer>() {
            public Integer apply(Integer x) {
                return x + 1;
            }
        });
        System.out.println("chain " + chain.get());
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(new Supplier<Integer>() {
            public Integer get() {
                return 1;
            }
        });
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(new Supplier<Integer>() {
            public Integer get() {
                return 2;
            }
        });
        CompletableFuture.allOf(a, b).get();
        System.out.println("all " + (a.get() + b.get()));
        CompletableFuture<Integer> slow = new CompletableFuture<Integer>();
        slow.orTimeout(100, TimeUnit.MILLISECONDS);
        try {
            slow.get();
        } catch (ExecutionException e) {
            System.out.println("timeout " + (e.getCause() instanceof TimeoutException));
        }
    }
}
