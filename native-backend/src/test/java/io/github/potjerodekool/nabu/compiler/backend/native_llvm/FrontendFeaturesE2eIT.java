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

/**
 * Frontend features natively: a lambda with a primitive body for an
 * Object-returning interface of the compiled class itself (it became a bare
 * function reference, "Unknown function reference"), boxing at
 * {@code return}, method references (static, unbound, overloaded) and a
 * nested-type static call as an argument. See FrontendRegressionRunTest for
 * the same cases on the JVM backend.
 */
class FrontendFeaturesE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void lambdasMethodReferencesAndBoxing() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                public class Main {
                    interface Task { Object run() throws Exception; }
                    static String p(String name, Task task) {
                        try {
                            return name + "=" + task.run();
                        } catch (Throwable t) {
                            return name + " threw " + t;
                        }
                    }
                    static Object prim(int i) {
                        if (i == 0) return true;
                        return i + 1;
                    }
                    static String twice(String s) { return s + s; }
                    public static void main(String[] args) throws Exception {
                        System.out.println(p("a", () -> java.util.List.of("a", "b").contains("b")) + " " + p("b", () -> "x".length()));
                        System.out.println(prim(0) + " " + prim(2));
                        java.util.function.Function<String, Integer> len = String::length;
                        java.util.function.Function<String, String> tw = Main::twice;
                        java.util.function.BinaryOperator<Integer> max = Math::max;
                        java.util.List<String> l = new java.util.ArrayList<>();
                        l.add("b");
                        l.add("a");
                        l.sort(String::compareTo);
                        System.out.println(len.apply("abc") + " " + tw.apply("x") + " " + max.apply(3, 7) + " " + l);
                        System.out.println(String.valueOf(Thread.State.valueOf("BLOCKED")));
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
            exe = walk.filter(p -> p.getFileName().toString().endsWith(".exe")).findFirst().orElseThrow();
        }
        final String keep = System.getProperty("nabu.keepExe");
        if (keep != null) {
            Files.copy(exe, Path.of(keep), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (Stream<Path> walk = Files.walk(out)) {
                final Path ll = walk.filter(p -> p.getFileName().toString().endsWith(".ll"))
                        .max(java.util.Comparator.comparingLong(p -> p.toFile().length())).orElse(null);
                if (ll != null) {
                    Files.copy(ll, Path.of(keep.substring(0, keep.length() - ".exe".length()) + ".ll"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        final Path log = tempDir.resolve("run.log");
        Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("programma hangt; output tot nu toe:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int exit = proc.exitValue();
        assertEquals("a=true b=1\ntrue 3\n3 xx 7 [a, b]\nBLOCKED\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
