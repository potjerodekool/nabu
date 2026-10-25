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
            exe = walk.filter(p -> p.getFileName().toString().endsWith(".exe")).findFirst().orElseThrow();
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
