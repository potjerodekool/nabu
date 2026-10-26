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
 * nested-type static call as an argument; a try-body assignment kept on
 * the exception path, a block lambda returning a lambda, and copies of
 * source int[]/long[] arrays (element size 8 in the header made
 * System.arraycopy copy twice the bytes). See FrontendRegressionRunTest for
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
                    static int calls = 0;
                    static String next() {
                        calls++;
                        return "v" + calls;
                    }
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
                        java.util.function.Supplier<String> once = next()::toUpperCase;
                        String first = once.get();
                        System.out.println(calls + " " + first + " " + once.get() + " " + calls);
                        String r = "init";
                        String keep = "k";
                        try {
                            keep = keep + "1";
                            Integer.parseInt("x");
                        } catch (NumberFormatException e) {
                            r = r + "-c";
                        }
                        System.out.println(r + " " + keep);
                        java.util.function.Supplier<java.util.function.IntSupplier> f = () -> { return () -> 7; };
                        System.out.println(f.get().getAsInt());
                        int[] src = {1, 2, 3, 4};
                        int[] dst = new int[6];
                        System.arraycopy(src, 1, dst, 2, 3);
                        int[] copy = java.util.Arrays.copyOf(src, 5);
                        long[] longs = {5L, 6L};
                        long[] longCopy = new long[2];
                        System.arraycopy(longs, 0, longCopy, 0, 2);
                        System.out.println(java.util.Arrays.toString(dst) + " " + java.util.Arrays.toString(copy)
                                + " " + longCopy[1]);
                        try {
                            System.arraycopy(src, 2, dst, 0, 9);
                            System.out.println("no exception");
                        } catch (IndexOutOfBoundsException e) {
                            System.out.println("out of bounds");
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
        assertEquals("a=true b=1\ntrue 3\n3 xx 7 [a, b]\nBLOCKED\n1 V1 V1 1\n"
                + "init-c k1\n7\n[0, 0, 2, 3, 4, 0] [1, 2, 3, 4, 0] 6\nout of bounds\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
