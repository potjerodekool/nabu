import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Main {
    static Callable<String> after(final int millis, final String result) {
        return new Callable<String>() {
            public String call() throws Exception {
                Thread.sleep(millis);
                return result;
            }
        };
    }

    public static void main(String[] args) throws Exception {
        BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
        queue.add("a");
        queue.offer("b");
        System.out.println("peek " + queue.peek() + " size " + queue.size());
        System.out.println("poll " + queue.poll() + " " + queue.poll() + " " + queue.poll());
        System.out.println("timed " + queue.poll(50, TimeUnit.MILLISECONDS));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CompletionService<String> service = new ExecutorCompletionService<String>(pool);
        service.submit(after(600, "slow"));
        service.submit(after(100, "fast"));
        service.submit(after(300, "middle"));
        StringBuilder order = new StringBuilder("completed");
        for (int i = 0; i < 3; i++) {
            order.append(' ').append(service.take().get());
        }
        System.out.println(order);

        List<Callable<String>> tasks = List.of(after(400, "late"), after(50, "early"));
        System.out.println("invokeAny " + pool.invokeAny(tasks));
        pool.shutdown();
    }
}
