import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Callable<Integer>> tasks = new ArrayList<Callable<Integer>>();
        for (int i = 1; i <= 3; i++) {
            final int n = i;
            tasks.add(new Callable<Integer>() {
                public Integer call() {
                    return n * n;
                }
            });
        }
        StringBuilder sb = new StringBuilder("invokeAll");
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            sb.append(' ').append(f.get());
        }
        System.out.println(sb);
        pool.shutdown();
    }
}
