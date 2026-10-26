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
