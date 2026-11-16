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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A ThreadLocal keeps its value for the life of the thread: the initial value is made once per thread. */
class ThreadLocalPersistsE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void threadLocalKeepsItsValue() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                public class Main {
                    static int made;
                    static final ThreadLocal<int[]> LOCAL = new ThreadLocal<int[]>() {
                        @Override
                        protected int[] initialValue() {
                            made++;
                            return new int[1];
                        }
                    };

                    static void use() {
                        LOCAL.get()[0]++;
                        LOCAL.get()[0]++;
                        LOCAL.get()[0]++;
                    }

                    static class Worker extends Thread {
                        public void run() {
                            use();
                            use();
                            System.out.println("worker " + LOCAL.get()[0] + " made " + made);
                        }
                    }

                    public static void main(String[] args) throws Exception {
                        use();
                        System.out.println("main " + LOCAL.get()[0] + " made " + made);
                        Thread t = new Worker();
                        t.start();
                        t.join();
                        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
                        for (int i = 0; i < 20; i++) {
                            pool.submit(new Runnable() { public void run() { use(); } }).get();
                        }
                        pool.shutdown();
                        System.out.println("pool made " + made);
                        use();
                        System.out.println("main " + LOCAL.get()[0] + " made " + made);
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), "[ ]\n");

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, System.getProperty("java.home"))
                .build();
        final var diagnostics = new java.util.ArrayList<String>();
        final var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        final Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(NativeToolchain::isExecutableOutput).findFirst().orElseThrow();
        }
        final Path log = tempDir.resolve("run.log");
        final Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("the program hangs");
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("main 3 made 1\nworker 6 made 2\npool made 4\nmain 6 made 4\n", output, "exit=" + proc.exitValue());
        assertEquals(0, proc.exitValue(), output);
    }
}
