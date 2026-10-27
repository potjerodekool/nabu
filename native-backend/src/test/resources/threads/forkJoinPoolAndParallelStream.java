import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        Future<Integer> future = pool.submit(new Callable<Integer>() {
            public Integer call() {
                return 5050;
            }
        });
        System.out.println(future.get());
        pool.shutdown();
        System.out.println(java.util.stream.IntStream.rangeClosed(1, 100).parallel().sum());
    }
}
