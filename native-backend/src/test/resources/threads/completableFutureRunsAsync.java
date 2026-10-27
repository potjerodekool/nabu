import java.util.concurrent.*;
import java.util.function.*;

public class Main {
    public static void main(String[] args) throws Exception {
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(new Supplier<Integer>() {
            public Integer get() {
                return 20;
            }
        }).thenApply(new Function<Integer, Integer>() {
            public Integer apply(Integer value) {
                return value + 22;
            }
        });
        System.out.println(future.get());
        CompletableFuture<Void> last = CompletableFuture.runAsync(new Runnable() {
            public void run() {
                System.out.println("done");
            }
        });
        last.get();
    }
}
