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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLVM inlines small methods, but a stack trace lists them all, as on a JVM: every method of the program that was
 * inlined into another one is still a frame of its own.
 */
class InlinedFramesE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void inlinedMethodsAreFramesOfTheTrace() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                public class Main {
                    static int zero = 0;

                    static int c(int x) {
                        return 10 / (x + zero);
                    }

                    static int b(int x) {
                        return c(x) + 1;
                    }

                    static int a(int x) {
                        return b(x) + 2;
                    }

                    static int viaList(java.util.List<String> list) {
                        return list.get(3).length();
                    }

                    public static void main(String[] args) {
                        try {
                            a(0);
                        } catch (ArithmeticException e) {
                            show(e);
                        }
                        try {
                            viaList(new java.util.ArrayList<String>());
                        } catch (IndexOutOfBoundsException e) {
                            show(e);
                        }
                    }

                    static void show(Throwable e) {
                        System.out.println(e);
                        for (StackTraceElement el : e.getStackTrace()) {
                            System.out.println("  " + el);
                        }
                    }
                }
                """, StandardCharsets.UTF_8);
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
        }
        final Path log = tempDir.resolve("run.log");
        Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("the program hangs; output so far:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(0, proc.exitValue(), output);
        final int second = output.indexOf("java.lang.IndexOutOfBounds");
        assertEquals("""
                java.lang.ArithmeticException: / by zero
                  Main.c(Main.java:5)
                  Main.b(Main.java:9)
                  Main.a(Main.java:13)
                  Main.main(Main.java:22)
                """, output.substring(0, second), output);
        // The runtime functions that make the exception are no part of its trace.
        assertTrue(!output.contains("nabu_") && !output.contains("jcl_"), output);
        assertTrue(output.endsWith("  Main.viaList(Main.java:17)\n  Main.main(Main.java:27)\n"), output);
    }
}
