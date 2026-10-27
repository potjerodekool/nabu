import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> a = executor.submit(new Callable<Integer>() {
                public Integer call() {
                    return 6;
                }
            });
            Future<Integer> b = executor.submit(new Callable<Integer>() {
                public Integer call() {
                    return 7;
                }
            });
            Future<Integer> c = executor.submit(new Callable<Integer>() {
                public Integer call() {
                    return 8;
                }
            });
            System.out.println(a.get() + " " + b.get() + " " + c.get());
        }
    }
}
