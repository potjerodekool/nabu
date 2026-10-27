import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Integer> future = executor.submit(new Callable<Integer>() {
            public Integer call() {
                return 6 * 7;
            }
        });
        System.out.println(future.get());
        executor.shutdown();
        System.out.println(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
