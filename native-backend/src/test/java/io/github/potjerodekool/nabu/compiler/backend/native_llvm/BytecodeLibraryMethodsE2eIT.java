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
 * JDK methods that jcl-lite used to implement in C, although the JDK has
 * bytecode for them, now compile from that bytecode. The C versions were
 * wrong: {@code UUID.fromString} returned a String, {@code StreamTokenizer}
 * never produced a token (its constructor was compiled, its methods were C),
 * {@code LineNumberReader} aborted because {@code BufferedReader} was provided
 * by the runtime (whose {@code readLine} always returned null), and
 * {@code getTargetException} returned the exception itself.
 * {@code printStackTrace} now also prints the cause. {@code Enum.valueOf}
 * no longer goes through a C map ({@code Class.enumConstantDirectory}).
 */
class BytecodeLibraryMethodsE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void libraryMethodsCompileFromBytecode() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println(java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000").toString());
                        java.io.StreamTokenizer t = new java.io.StreamTokenizer(new java.io.StringReader("word 42"));
                        t.nextToken();
                        System.out.println(t.sval);
                        t.nextToken();
                        System.out.println((int) t.nval);
                        java.io.LineNumberReader r = new java.io.LineNumberReader(new java.io.StringReader("l1\\nl2"));
                        System.out.println(r.readLine() + " " + r.readLine() + " " + r.getLineNumber());
                        System.out.println(new java.io.BufferedReader(new java.io.StringReader("x")).readLine());
                        Exception cause = new IllegalStateException("inner");
                        System.out.println(new java.lang.reflect.InvocationTargetException(cause).getTargetException().getMessage());
                        System.out.println("AbC".toLowerCase() + "AbC".toUpperCase() + "hello".substring(2) + "hello".indexOf('l') + "hello".indexOf("l", 3));
                        System.out.println(String.join("|", "a,b,c".split(",")));
                        System.out.println(Double.valueOf(2.5));
                        java.io.StringWriter w = new java.io.StringWriter();
                        new RuntimeException("outer", cause).printStackTrace(new java.io.PrintWriter(w));
                        System.out.println(w.toString().replace(System.lineSeparator(), "/"));
                        java.util.concurrent.TimeUnit unit = java.util.concurrent.TimeUnit.valueOf("SECONDS");
                        System.out.println(unit.name());
                        Thread.State state = Thread.State.valueOf("BLOCKED");
                        System.out.println(state.name());
                        try {
                            java.util.concurrent.TimeUnit.valueOf("WEEKS");
                        } catch (IllegalArgumentException e) {
                            System.out.println(e.getMessage());
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
        assertEquals("123e4567-e89b-12d3-a456-426614174000\nword\n42\nl1 l2 2\nx\ninner\nabcABCllo23\na|b|c\n2.5\n"
                + "java.lang.RuntimeException: outer/\tat Main.main(Main.java:18)/Caused by: java.lang.IllegalStateException: inner/\tat Main.main(Main.java:11)/\n"
                + "SECONDS\nBLOCKED\nNo enum constant java.util.concurrent.TimeUnit.WEEKS\n",
                output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
