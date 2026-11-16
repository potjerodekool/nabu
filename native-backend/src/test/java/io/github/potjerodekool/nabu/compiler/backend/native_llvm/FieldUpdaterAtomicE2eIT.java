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

/** The compare-and-set of an AtomicIntegerFieldUpdater is atomic: threads that race for a flag never both win. */
class FieldUpdaterAtomicE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void compareAndSetOfAFieldUpdaterIsExclusive() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path libClasses = tempDir.resolve("libclasses");
        Files.createDirectories(libClasses);
        // A library class (its bytecode is what the compiler rewrites; source code uses the JDK's updater)
        final Path libSrc = tempDir.resolve("libsrc");
        Files.createDirectories(libSrc.resolve("lib"));
        Files.writeString(libSrc.resolve("lib/Claim.java"), """
                package lib;

                import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

                public class Claim {
                    volatile int state;
                    volatile int inside;
                    int violations;

                    private static final AtomicIntegerFieldUpdater<Claim> STATE =
                            AtomicIntegerFieldUpdater.newUpdater(Claim.class, "state");

                    public boolean tryEnter() { return STATE.compareAndSet(this, 0, 1); }

                    public void exit() { STATE.set(this, 0); }

                    /** Only the thread that entered may be here: the plain counter shows when two are. */
                    public void work() {
                        inside++;
                        // stay a while: two threads that both entered then overlap and see each other
                        for (int k = 0; k < 300; k++) {
                            if (inside != 1) {
                                violations++;
                                break;
                            }
                        }
                        inside--;
                    }

                    public int violations() { return violations; }
                }
                """);
        final int javac = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d",
                libClasses.toString(), libSrc.resolve("lib/Claim.java").toString());
        assertEquals(0, javac);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                import lib.Claim;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        final Claim claim = new Claim();
                        Thread[] threads = new Thread[4];
                        for (int t = 0; t < threads.length; t++) {
                            threads[t] = new Thread() {
                                public void run() {
                                    for (int i = 0; i < 300000; i++) {
                                        if (claim.tryEnter()) {
                                            claim.work();
                                            claim.exit();
                                        }
                                    }
                                }
                            };
                        }
                        for (Thread thread : threads) {
                            thread.start();
                        }
                        for (Thread thread : threads) {
                            thread.join();
                        }
                        System.out.println("violations " + claim.violations());
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
                .option(CompilerOption.CLASS_PATH, libClasses.toString())
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
        final String keep = System.getProperty("nabu.keepExe");
        if (keep != null) { Files.copy(exe, Path.of(keep), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        final Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("the program hangs");
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("violations 0\n", output, "exit=" + proc.exitValue());
        assertEquals(0, proc.exitValue(), output);
    }
}
