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
 * Arrays know their class: getClass() on an array (it has no object header)
 * read its length as a type pointer and answered String; Array.newInstance
 * made untyped reference arrays, also for int.class; Arrays.copyOf and
 * List.toArray(T[]) lost the element type.
 */
class ArrayClassE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void arraysKnowTheirClass() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                public class Main {
                    public static void main(String[] args) {
                        String[] s = {"a"};
                        System.out.println(s.getClass().getName());
                        int[] i = new int[2];
                        System.out.println(i.getClass().getName());
                        String[] c = java.util.Arrays.copyOf(s, 3);
                        System.out.println(c.getClass().getName() + " " + c.length);
                        int[] ai = (int[]) java.lang.reflect.Array.newInstance(int.class, 4);
                        ai[3] = 7;
                        int[] copy = new int[4];
                        System.arraycopy(ai, 0, copy, 0, 4);
                        System.out.println(ai.getClass().getName() + " " + copy[3]);
                        System.out.println(s.clone().getClass().getName());
                        java.util.List<String> list = new java.util.ArrayList<>();
                        list.add("x");
                        String[] ta = list.toArray(new String[0]);
                        System.out.println(ta.getClass().getName() + ta[0]);
                        int[][] m = new int[2][3];
                        System.out.println(m.getClass().getName() + " " + m[1].getClass().getName() + " " + m[1].length);
                        System.out.println(args.getClass().getName() + " " + args.length);
                        Object o = s;
                        Object oi = i;
                        System.out.println(o.getClass().getName() + " " + oi.getClass().getSimpleName());
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
        assertEquals("[Ljava.lang.String;\n[I\n[Ljava.lang.String; 3\n[I 7\n[Ljava.lang.String;\n"
                + "[Ljava.lang.String;x\n[[I [I 3\n[Ljava.lang.String; 0\n[Ljava.lang.String; int[]\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
