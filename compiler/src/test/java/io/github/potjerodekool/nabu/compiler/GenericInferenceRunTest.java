package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Type inference with generic methods, the diamond and lambdas: programs of the kind the
 * {@code java.util.concurrent} classes are used in.
 */
class GenericInferenceRunTest {

    @TempDir
    Path tempDir;

    private Class<?> compileAndLoad(final String className, final String code) throws Exception {
        final var srcDir = tempDir.resolve("src");
        final var javaFile = srcDir.resolve("mini/" + className + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, code);

        final var outDir = tempDir.resolve("out");
        final var rootPath = "C:\\projects\\nabu\\compiler";
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();

        final var compiler = new NabuCompiler();
        final var errors = new AtomicInteger();
        final var messages = new java.util.ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                errors.incrementAndGet();
                messages.add(d.getMessage(java.util.Locale.ROOT).toString());
            }
        });
        final var rc = compiler.compile(options);
        assertEquals(0, errors.get(), String.join("\n", messages));
        assertEquals(0, rc, String.join("\n", messages));

        final var loader = new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()},
                getClass().getClassLoader());
        return loader.loadClass("mini." + className);
    }

    @Test
    void diamondAssignedToInterface() throws Exception {
        final var cls = compileAndLoad("Diamond", """
                package mini;
                import java.util.concurrent.ArrayBlockingQueue;
                import java.util.concurrent.BlockingQueue;
                public class Diamond {
                    public static int start() throws Exception {
                        final BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(4);
                        queue.put(5);
                        int v = queue.take();
                        return v;
                    }
                }
                """);
        assertEquals(5, cls.getMethod("start").invoke(null));
    }

    @Test
    void lambdaAsRunnableArgument() throws Exception {
        final var cls = compileAndLoad("LambdaRunnable", """
                package mini;
                public class LambdaRunnable {
                    static int counter;
                    public static int start() throws Exception {
                        Thread t = new Thread(() -> counter++);
                        t.start();
                        t.join();
                        return counter;
                    }
                }
                """);
        assertEquals(1, cls.getMethod("start").invoke(null));
    }

    @Test
    void lambdaCallingAMethodOfACapturedObject() throws Exception {
        final var cls = compileAndLoad("LambdaCapture", """
                package mini;
                import java.util.concurrent.CountDownLatch;
                public class LambdaCapture {
                    public static long start() throws Exception {
                        final CountDownLatch latch = new CountDownLatch(1);
                        Thread t = new Thread(() -> latch.countDown());
                        t.start();
                        latch.await();
                        return latch.getCount();
                    }
                }
                """);
        assertEquals(0L, cls.getMethod("start").invoke(null));
    }

    @Test
    void supplyAsyncThenApply() throws Exception {
        final var cls = compileAndLoad("Chain", """
                package mini;
                import java.util.concurrent.CompletableFuture;
                public class Chain {
                    public static int start() throws Exception {
                        CompletableFuture<Integer> chain = CompletableFuture.supplyAsync(() -> 20).thenApply(x -> x + 1);
                        return chain.get();
                    }
                }
                """);
        assertEquals(21, cls.getMethod("start").invoke(null));
    }

    @Test
    void scheduleWithLambda() throws Exception {
        final var cls = compileAndLoad("Scheduled", """
                package mini;
                import java.util.concurrent.Executors;
                import java.util.concurrent.ScheduledExecutorService;
                import java.util.concurrent.ScheduledFuture;
                import java.util.concurrent.TimeUnit;
                public class Scheduled {
                    public static int start() throws Exception {
                        ScheduledExecutorService ses = Executors.newScheduledThreadPool(1);
                        ScheduledFuture<Integer> f = ses.schedule(() -> 42, 10, TimeUnit.MILLISECONDS);
                        int v = f.get();
                        ses.shutdown();
                        return v;
                    }
                }
                """);
        assertEquals(42, cls.getMethod("start").invoke(null));
    }

    @Test
    void optionalMapAsPrintArgument() throws Exception {
        final var cls = compileAndLoad("Printed", """
                package mini;
                import java.util.Optional;
                import java.util.function.UnaryOperator;
                public class Printed {
                    public static String start() {
                        UnaryOperator<String> op = new UnaryOperator<String>() {
                            public String apply(String s) {
                                return s + "!";
                            }
                        };
                        java.io.StringWriter out = new java.io.StringWriter();
                        java.io.PrintWriter w = new java.io.PrintWriter(out);
                        w.println(Optional.of("b").map(op).get());
                        w.flush();
                        return out.toString().trim();
                    }
                }
                """);
        assertEquals("b!", cls.getMethod("start").invoke(null));
    }
}
