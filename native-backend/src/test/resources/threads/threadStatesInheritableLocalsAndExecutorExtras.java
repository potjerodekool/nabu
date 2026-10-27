import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    static final InheritableThreadLocal<String> local = new InheritableThreadLocal<String>();
    static final Object lock = new Object();

    static void waitFor(Thread t, Thread.State state) throws Exception {
        for (int i = 0; i < 2000 && t.getState() != state; i++) {
            Thread.sleep(1);
        }
        System.out.println("state " + t.getState());
    }

    public static void main(String[] args) throws Exception {
        local.set("parent");
        Thread child = new Thread(new Runnable() {
            public void run() {
                System.out.println("inherited " + local.get());
            }
        });
        child.start();
        child.join();

        Thread sleeper = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(300);
                    synchronized (lock) {
                        lock.wait();
                    }
                    synchronized (lock) {
                    }
                } catch (InterruptedException e) {
                }
            }
        });
        System.out.println("state " + sleeper.getState());
        sleeper.start();
        waitFor(sleeper, Thread.State.TIMED_WAITING);
        waitFor(sleeper, Thread.State.WAITING);
        synchronized (lock) {
            lock.notify();
            Thread.sleep(100);
            waitFor(sleeper, Thread.State.BLOCKED);
            System.out.println("holds " + Thread.holdsLock(lock) + " " + Thread.holdsLock(Main.class));
        }
        sleeper.join();
        waitFor(sleeper, Thread.State.TERMINATED);

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
        System.out.println("invokeAny " + pool.invokeAny(List.of(new Callable<String>() {
            public String call() {
                return "ok";
            }
        })));
        pool.shutdown();

        final AtomicInteger counter = new AtomicInteger();
        ExecutorService named = Executors.newFixedThreadPool(2, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                return new Thread(r, "worker-" + counter.incrementAndGet());
            }
        });
        Future<String> a = named.submit(new Callable<String>() {
            public String call() throws Exception {
                Thread.sleep(100);
                return Thread.currentThread().getName();
            }
        });
        Future<String> b = named.submit(new Callable<String>() {
            public String call() throws Exception {
                Thread.sleep(100);
                return Thread.currentThread().getName();
            }
        });
        String first = a.get();
        String second = b.get();
        System.out.println("factory " + (first.compareTo(second) < 0 ? first + " " + second : second + " " + first));
        named.shutdown();

        final boolean[] interrupted = new boolean[1];
        ExecutorService single = Executors.newSingleThreadExecutor();
        single.submit(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    interrupted[0] = true;
                }
            }
        });
        Thread.sleep(100);
        single.shutdownNow();
        System.out.println("shutdownNow interrupted " + single.awaitTermination(5, TimeUnit.SECONDS) + "");
        System.out.println("stacks " + (Thread.getAllStackTraces().size() >= 1));
    }
}
