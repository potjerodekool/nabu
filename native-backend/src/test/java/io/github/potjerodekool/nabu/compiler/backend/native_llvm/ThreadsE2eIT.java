package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Threads in a native executable: they are real native threads that run one at a time and switch where
 * they block. The expected output is what the JVM prints.
 */
class ThreadsE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void threadsStartJoinWaitAndInterrupt() throws Exception {
        assertEquals("""
                main main
                child Thread-0
                joined false
                join true
                got 42
                interrupted false
                count 4000
                main done
                late
                """, run("""
                public class Main {
                    static final Object lock = new Object();
                    static int value = 0;
                    static int count = 0;

                    public static void main(String[] args) throws Exception {
                        System.out.println("main " + Thread.currentThread().getName());
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                System.out.println("child " + Thread.currentThread().getName());
                            }
                        });
                        t.start();
                        t.join();
                        System.out.println("joined " + t.isAlive());
                        System.out.println("join " + t.join(java.time.Duration.ofMillis(10)));
                        Thread consumer = new Thread(new Runnable() {
                            public void run() {
                                synchronized (lock) {
                                    while (value == 0) {
                                        try {
                                            lock.wait();
                                        } catch (InterruptedException e) {
                                            return;
                                        }
                                    }
                                    System.out.println("got " + value);
                                }
                            }
                        });
                        consumer.start();
                        Thread.sleep(50);
                        synchronized (lock) {
                            value = 42;
                            lock.notifyAll();
                        }
                        consumer.join();
                        Thread sleeper = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    Thread.sleep(10000);
                                    System.out.println("not interrupted");
                                } catch (InterruptedException e) {
                                    System.out.println("interrupted " + Thread.currentThread().isInterrupted());
                                }
                            }
                        });
                        sleeper.start();
                        Thread.sleep(50);
                        sleeper.interrupt();
                        sleeper.join();
                        Thread[] many = new Thread[4];
                        for (int i = 0; i < many.length; i++) {
                            many[i] = new Thread(new Runnable() {
                                public void run() {
                                    for (int k = 0; k < 1000; k++) {
                                        synchronized (lock) {
                                            count++;
                                        }
                                        if (k % 100 == 0) {
                                            Thread.yield();
                                        }
                                    }
                                }
                            });
                            many[i].start();
                        }
                        for (int i = 0; i < many.length; i++) {
                            many[i].join();
                        }
                        System.out.println("count " + count);
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                }
                                System.out.println("late");
                            }
                        }).start();
                        System.out.println("main done");
                    }
                }
                """));
    }

    @Test
    void threadSubclass() throws Exception {
        assertEquals("""
                worker running
                Thread-0 true
                """, run("""
                public class Main {
                    static class Worker extends Thread {
                        private int value;

                        Worker(String name, int value) {
                            super(name);
                            this.value = value;
                        }

                        Worker(int value) {
                            this.value = value;
                        }

                        public void run() {
                            System.out.println(getName() + " running");
                        }
                    }

                    public static void main(String[] args) throws Exception {
                        Worker named = new Worker("worker", 1);
                        named.start();
                        named.join();
                        Worker plain = new Worker(2);
                        System.out.println(plain.getName() + " " + (plain.getState() == Thread.State.NEW));
                    }
                }
                """));
    }

    @Test
    void completableFutureRunsAsync() throws Exception {
        assertEquals("""
                42
                done
                """, run("""
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
                """));
    }

    @Test
    void forkJoinPoolAndParallelStream() throws Exception {
        assertEquals("""
                5050
                5050
                """, run("""
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
                """));
    }

    @Test
    void timerRunsTask() throws Exception {
        assertEquals("""
                tick
                end
                """, run("""
                public class Main {
                    public static void main(String[] args) throws Exception {
                        java.util.Timer timer = new java.util.Timer();
                        timer.schedule(new java.util.TimerTask() {
                            public void run() {
                                System.out.println("tick");
                            }
                        }, 50);
                        Thread.sleep(300);
                        timer.cancel();
                        System.out.println("end");
                    }
                }
                """));
    }

    @Test
    void spinningThreadsLetOthersRun() throws Exception {
        assertEquals("""
                saw ready
                interrupt seen
                """, run("""
                public class Main {
                    static volatile boolean ready;

                    public static void main(String[] args) throws Exception {
                        Thread watcher = new Thread(new Runnable() {
                            public void run() {
                                while (!ready) {
                                }
                                System.out.println("saw ready");
                            }
                        });
                        watcher.start();
                        Thread.sleep(50);
                        ready = true;
                        watcher.join();
                        Thread looper = new Thread(new Runnable() {
                            public void run() {
                                while (!Thread.currentThread().isInterrupted()) {
                                }
                                System.out.println("interrupt seen");
                            }
                        });
                        looper.start();
                        Thread.sleep(50);
                        looper.interrupt();
                        looper.join();
                    }
                }
                """));
    }

    @Test
    void executorRunsTasks() throws Exception {
        assertEquals("""
                42
                true
                """, run("""
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
                """));
    }

    @Test
    void uncaughtExceptionHandlers() throws Exception {
        assertEquals("""
                get true
                own t1 boom
                default t2 bang
                after null
                """, run("""
                public class Main {
                    public static void main(String[] args) throws Exception {
                        Thread t1 = new Thread(new Runnable() {
                            public void run() {
                                throw new IllegalStateException("boom");
                            }
                        }, "t1");
                        t1.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                            public void uncaughtException(Thread t, Throwable e) {
                                System.out.println("own " + t.getName() + " " + e.getMessage());
                            }
                        });
                        System.out.println(t1.getUncaughtExceptionHandler() != null ? "get true" : "get false");
                        t1.start();
                        t1.join();
                        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                            public void uncaughtException(Thread t, Throwable e) {
                                System.out.println("default " + t.getName() + " " + e.getMessage());
                            }
                        });
                        Thread t2 = new Thread(new Runnable() {
                            public void run() {
                                throw new IllegalStateException("bang");
                            }
                        }, "t2");
                        t2.start();
                        t2.join();
                        System.out.println("after " + t1.getUncaughtExceptionHandler());
                    }
                }
                """));
    }

    @Test
    void threadGroupTree() throws Exception {
        assertEquals("""
                main system
                worker group workers
                parent main
                active 2
                groups 1
                ended null
                """, run("""
                public class Main {
                    public static void main(String[] args) throws Exception {
                        ThreadGroup main = Thread.currentThread().getThreadGroup();
                        System.out.println(main.getName() + " " + main.getParent().getName());
                        ThreadGroup workers = new ThreadGroup(main, "workers");
                        final Object lock = new Object();
                        Thread t = new Thread(workers, new Runnable() {
                            public void run() {
                                synchronized (lock) {
                                    try {
                                        lock.wait();
                                    } catch (InterruptedException e) {
                                    }
                                }
                            }
                        }, "worker");
                        t.start();
                        Thread.sleep(50);
                        System.out.println(t.getName() + " group " + t.getThreadGroup().getName());
                        System.out.println("parent " + workers.getParent().getName());
                        System.out.println("active " + main.activeCount() + (workers.activeCount() == 1 ? "" : " wrong"));
                        System.out.println("groups " + main.activeGroupCount());
                        t.interrupt();
                        t.join();
                        System.out.println("ended " + t.getThreadGroup());
                    }
                }
                """));
    }

    @Test
    void threadGroupList() throws Exception {
        assertEquals("""
                java.lang.ThreadGroup[name=workers,maxpri=10]
                    listed
                ended
                """, run("""
                public class Main {
                    public static void main(String[] args) throws Exception {
                        ThreadGroup workers = new ThreadGroup("workers");
                        final Object lock = new Object();
                        Thread t = new Thread(workers, new Runnable() {
                            public void run() {
                                synchronized (lock) {
                                    try {
                                        lock.wait();
                                    } catch (InterruptedException e) {
                                    }
                                }
                            }
                        }, "listed");
                        t.start();
                        Thread.sleep(50);
                        workers.list();
                        t.interrupt();
                        t.join();
                        System.out.println("ended");
                    }
                }
                """).replaceAll("(?m)^(\\s+)Thread\\[.*$", "$1listed"));
    }

    @Test
    void structuredTaskScope() throws Exception {
        assertEquals("""
                30 12
                """, run("""
                import java.util.concurrent.Callable;
                import java.util.concurrent.StructuredTaskScope;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        try (StructuredTaskScope<Object, Void> scope = StructuredTaskScope.open()) {
                            StructuredTaskScope.Subtask<Integer> a = scope.fork(new Callable<Integer>() {
                                public Integer call() {
                                    return 30;
                                }
                            });
                            StructuredTaskScope.Subtask<Integer> b = scope.fork(new Callable<Integer>() {
                                public Integer call() {
                                    return 12;
                                }
                            });
                            scope.join();
                            System.out.println(a.get() + " " + b.get());
                        }
                    }
                }
                """));
    }

    @Test
    void structuredTaskScopeFailureAndScopedValue() throws Exception {
        assertEquals("""
                inherited alice
                failed java.lang.IllegalStateException: boom
                """, run("""
                import java.util.concurrent.Callable;
                import java.util.concurrent.StructuredTaskScope;

                public class Main {
                    static final ScopedValue<String> USER = ScopedValue.newInstance();

                    public static void main(String[] args) throws Exception {
                        ScopedValue.where(USER, "alice").run(new Runnable() {
                            public void run() {
                                try (StructuredTaskScope<Object, Void> scope = StructuredTaskScope.open()) {
                                    StructuredTaskScope.Subtask<String> a = scope.fork(new Callable<String>() {
                                        public String call() {
                                            return "inherited " + USER.get();
                                        }
                                    });
                                    scope.join();
                                    System.out.println(a.get());
                                } catch (Exception e) {
                                    System.out.println("error " + e);
                                }
                            }
                        });
                        try (StructuredTaskScope<Object, Void> scope = StructuredTaskScope.open()) {
                            StructuredTaskScope.Subtask<String> f = scope.fork(new Callable<String>() {
                                public String call() {
                                    throw new IllegalStateException("boom");
                                }
                            });
                            scope.join();
                            System.out.println("not reached");
                        } catch (RuntimeException e) {
                            System.out.println("failed " + e.getCause());
                        }
                    }
                }
                """));
    }

    @Test
    void structuredTaskScopeAnySuccessful() throws Exception {
        assertEquals("""
                any 7
                """, run("""
                import java.time.Duration;
                import java.util.concurrent.Callable;
                import java.util.concurrent.StructuredTaskScope;
                import java.util.function.UnaryOperator;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        try (StructuredTaskScope<Integer, Integer> scope =
                                     StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>anySuccessfulResultOrThrow())) {
                            scope.fork(new Callable<Integer>() {
                                public Integer call() throws Exception {
                                    Thread.sleep(10000);
                                    return 1;
                                }
                            });
                            scope.fork(new Callable<Integer>() {
                                public Integer call() {
                                    return 7;
                                }
                            });
                            System.out.println("any " + scope.join());
                        }
                    }
                }
                """));
    }

    @Test
    void structuredTaskScopeAllSuccessful() throws Exception {
        assertEquals("""
                all 3
                """, run("""
                import java.time.Duration;
                import java.util.concurrent.Callable;
                import java.util.concurrent.StructuredTaskScope;
                import java.util.function.UnaryOperator;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        try (StructuredTaskScope<Integer, java.util.stream.Stream<StructuredTaskScope.Subtask<Integer>>> scope =
                                     StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>allSuccessfulOrThrow())) {
                            for (int i = 0; i < 3; i++) {
                                scope.fork(new Callable<Integer>() {
                                    public Integer call() {
                                        return 1;
                                    }
                                });
                            }
                            System.out.println("all " + scope.join().count());
                        }
                    }
                }
                """));
    }

    @Test
    void structuredTaskScopeTimeout() throws Exception {
        assertEquals("""
                timeout true
                """, run("""
                import java.time.Duration;
                import java.util.concurrent.Callable;
                import java.util.concurrent.StructuredTaskScope;
                import java.util.function.UnaryOperator;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        UnaryOperator<StructuredTaskScope.Configuration> config = new UnaryOperator<StructuredTaskScope.Configuration>() {
                            public StructuredTaskScope.Configuration apply(StructuredTaskScope.Configuration cf) {
                                return cf.withTimeout(Duration.ofMillis(100));
                            }
                        };
                        try (StructuredTaskScope<Integer, Void> scope =
                                     StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>awaitAllSuccessfulOrThrow(), config)) {
                            scope.fork(new Callable<Integer>() {
                                public Integer call() throws Exception {
                                    Thread.sleep(10000);
                                    return 1;
                                }
                            });
                            scope.join();
                            System.out.println("timeout false");
                        } catch (RuntimeException e) {
                            System.out.println("timeout " + (e instanceof StructuredTaskScope.TimeoutException));
                        }
                    }
                }
                """));
    }

    /** UnaryOperator declares no method: its itable slots come from Function, which has to count as implemented. */
    @Test
    void anonymousClassOfInterfaceWithoutOwnMethods() throws Exception {
        assertEquals("""
                a!
                b!
                """, run("""
                import java.util.function.Function;
                import java.util.function.UnaryOperator;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        UnaryOperator<String> op = new UnaryOperator<String>() {
                            public String apply(String s) {
                                return s + "!";
                            }
                        };
                        Function<String, String> f = op;
                        System.out.println(f.apply("a"));
                        String mapped = java.util.Optional.of("b").map(op).get();
                        System.out.println(mapped);
                    }
                }
                """));
    }

    @Test
    void locksAndSynchronizers() throws Exception {
        assertEquals("""
                lock 2000
                latch 0
                semaphore 3
                barrier 3
                readwrite 5
                """, run("""
                import java.util.concurrent.CountDownLatch;
                import java.util.concurrent.CyclicBarrier;
                import java.util.concurrent.Semaphore;
                import java.util.concurrent.atomic.AtomicInteger;
                import java.util.concurrent.locks.ReentrantLock;
                import java.util.concurrent.locks.ReentrantReadWriteLock;

                public class Main {
                    static int counter;

                    public static void main(String[] args) throws Exception {
                        final ReentrantLock lock = new ReentrantLock();
                        Thread[] threads = new Thread[2];
                        for (int t = 0; t < threads.length; t++) {
                            threads[t] = new Thread(new Runnable() {
                                public void run() {
                                    for (int i = 0; i < 1000; i++) {
                                        lock.lock();
                                        try {
                                            counter++;
                                        } finally {
                                            lock.unlock();
                                        }
                                    }
                                }
                            });
                            threads[t].start();
                        }
                        for (Thread t : threads) {
                            t.join();
                        }
                        System.out.println("lock " + counter);

                        final CountDownLatch latch = new CountDownLatch(3);
                        for (int i = 0; i < 3; i++) {
                            new Thread(new Runnable() {
                                public void run() {
                                    latch.countDown();
                                }
                            }).start();
                        }
                        latch.await();
                        System.out.println("latch " + latch.getCount());

                        final Semaphore semaphore = new Semaphore(0);
                        for (int i = 0; i < 3; i++) {
                            new Thread(new Runnable() {
                                public void run() {
                                    semaphore.release();
                                }
                            }).start();
                        }
                        semaphore.acquire(3);
                        System.out.println("semaphore 3");

                        final CyclicBarrier barrier = new CyclicBarrier(3);
                        final AtomicInteger passed = new AtomicInteger();
                        Thread[] b = new Thread[2];
                        for (int i = 0; i < 2; i++) {
                            b[i] = new Thread(new Runnable() {
                                public void run() {
                                    try {
                                        barrier.await();
                                        passed.incrementAndGet();
                                    } catch (Exception e) {
                                    }
                                }
                            });
                            b[i].start();
                        }
                        barrier.await();
                        for (Thread t : b) {
                            t.join();
                        }
                        System.out.println("barrier " + (passed.get() + 1));

                        final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
                        rw.writeLock().lock();
                        counter = 5;
                        rw.writeLock().unlock();
                        rw.readLock().lock();
                        System.out.println("readwrite " + counter);
                        rw.readLock().unlock();
                    }
                }
                """));
    }

    @Test
    void blockingQueues() throws Exception {
        assertEquals("""
                sum 4950
                sync 7
                priority 1 2 3
                """, run("""
                import java.util.concurrent.ArrayBlockingQueue;
                import java.util.concurrent.PriorityBlockingQueue;
                import java.util.concurrent.SynchronousQueue;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        final ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<Integer>(4);
                        Thread producer = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    for (int i = 0; i < 100; i++) {
                                        queue.put(i);
                                    }
                                    queue.put(-1);
                                } catch (InterruptedException e) {
                                }
                            }
                        });
                        producer.start();
                        int sum = 0;
                        while (true) {
                            int v = queue.take();
                            if (v < 0) {
                                break;
                            }
                            sum += v;
                        }
                        System.out.println("sum " + sum);

                        final SynchronousQueue<Integer> sync = new SynchronousQueue<Integer>();
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    sync.put(7);
                                } catch (InterruptedException e) {
                                }
                            }
                        }).start();
                        System.out.println("sync " + sync.take());

                        PriorityBlockingQueue<Integer> pq = new PriorityBlockingQueue<Integer>();
                        pq.add(3);
                        pq.add(1);
                        pq.add(2);
                        int first = pq.take();
                        int second = pq.take();
                        int third = pq.take();
                        System.out.println("priority " + first + " " + second + " " + third);
                    }
                }
                """));
    }

    @Test
    void scheduledExecutorAndCompletableFuture() throws Exception {
        assertEquals("""
                scheduled 42
                fixed rate ran
                chain 21
                all 3
                timeout true
                """, run("""
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
                """));
    }

    @Test
    void lambdasWithThreadsAndFutures() throws Exception {
        assertEquals("""
                thread 3
                latch 0
                scheduled 42
                chain 21
                mapped b!
                """, run("""
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
                """));
    }

    @Test
    void virtualThreads() throws Exception {
        assertEquals("""
                virtual true daemon true
                name v1
                hello from v1
                started 6
                """, run("""
                import java.util.concurrent.atomic.AtomicInteger;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        Thread v = Thread.ofVirtual().name("v1").unstarted(new Runnable() {
                            public void run() {
                                System.out.println("hello from " + Thread.currentThread().getName());
                            }
                        });
                        System.out.println("virtual " + v.isVirtual() + " daemon " + v.isDaemon());
                        System.out.println("name " + v.getName());
                        v.start();
                        v.join();
                        final AtomicInteger count = new AtomicInteger();
                        Thread[] threads = new Thread[6];
                        for (int i = 0; i < threads.length; i++) {
                            threads[i] = Thread.startVirtualThread(new Runnable() {
                                public void run() {
                                    count.incrementAndGet();
                                }
                            });
                        }
                        for (Thread t : threads) {
                            t.join();
                        }
                        System.out.println("started " + count.get());
                    }
                }
                """));
    }

    @Test
    void virtualThreadExecutor() throws Exception {
        assertEquals("""
                6 7 8
                """, run("""
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
                """));
    }

    @Test
    void scopedValues() throws Exception {
        assertEquals("""
                outside false
                inside alice
                nested bob
                back alice
                call carol!
                after false none
                virtual false
                """, run("""
                public class Main {
                    static final ScopedValue<String> USER = ScopedValue.newInstance();

                    public static void main(String[] args) throws Exception {
                        System.out.println("outside " + USER.isBound());
                        ScopedValue.where(USER, "alice").run(new Runnable() {
                            public void run() {
                                System.out.println("inside " + USER.get());
                                ScopedValue.where(USER, "bob").run(new Runnable() {
                                    public void run() {
                                        System.out.println("nested " + USER.get());
                                    }
                                });
                                System.out.println("back " + USER.get());
                            }
                        });
                        String r = ScopedValue.where(USER, "carol").call(new ScopedValue.CallableOp<String, RuntimeException>() {
                            public String call() {
                                return USER.get() + "!";
                            }
                        });
                        System.out.println("call " + r);
                        System.out.println("after " + USER.isBound() + " " + USER.orElse("none"));
                        Thread v = Thread.ofVirtual().unstarted(new Runnable() {
                            public void run() {
                                System.out.println("virtual " + USER.isBound());
                            }
                        });
                        v.start();
                        v.join();
                    }
                }
                """));
    }

    @Test
    void threadGroupInterruptAndPriority() throws Exception {
        assertEquals("""
                active 2 groups 1
                interrupted true true
                destroyed false daemon false maxpri 10
                sub maxpri 3
                java.lang.ThreadGroup[name=g,maxpri=3]
                true false
                done
                """, run("""
                public class Main {
                    static volatile boolean tInterrupted;
                    static volatile boolean uInterrupted;

                    public static void main(String[] args) throws Exception {
                        ThreadGroup g = new ThreadGroup("g");
                        ThreadGroup sub = new ThreadGroup(g, "sub");
                        Thread t = new Thread(g, new Runnable() {
                            public void run() {
                                try {
                                    Thread.sleep(5000);
                                } catch (InterruptedException e) {
                                    tInterrupted = true;
                                }
                            }
                        }, "t");
                        Thread u = new Thread(sub, new Runnable() {
                            public void run() {
                                try {
                                    Thread.sleep(5000);
                                } catch (InterruptedException e) {
                                    uInterrupted = true;
                                }
                            }
                        }, "u");
                        t.start();
                        u.start();
                        Thread.sleep(100);
                        System.out.println("active " + g.activeCount() + " groups " + g.activeGroupCount());
                        g.interrupt();
                        t.join();
                        u.join();
                        System.out.println("interrupted " + tInterrupted + " " + uInterrupted);
                        System.out.println("destroyed " + g.isDestroyed() + " daemon " + g.isDaemon() + " maxpri " + g.getMaxPriority());
                        g.setMaxPriority(3);
                        System.out.println("sub maxpri " + sub.getMaxPriority());
                        System.out.println(g);
                        System.out.println(g.parentOf(sub) + " " + sub.parentOf(g));
                        g.destroy();
                        System.out.println("done");
                    }
                }
                """));
    }

    private String run(final String mainSource) throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), mainSource, StandardCharsets.UTF_8);
        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();
        var diagnostics = new java.util.ArrayList<String>();
        var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(NativeToolchain::isExecutableOutput).findFirst().orElseThrow();
        }
        final String keep = System.getProperty("nabu.keepExe");
        if (keep != null) {
            Files.copy(exe, Path.of(keep), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (Stream<Path> walk = Files.walk(out)) {
                final Path ll = walk.filter(p -> p.getFileName().toString().endsWith(".ll"))
                        .max(java.util.Comparator.comparingLong(p -> p.toFile().length())).orElse(null);
                if (ll != null) {
                    Files.copy(ll, Path.of(keep.substring(0, keep.length() - ".exe".length()) + ".ll"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        final Path log = tempDir.resolve("run.log");
        Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("the program hangs; output so far:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(0, proc.exitValue(), output);
        return output;
    }
}
