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
 * Exceptions have a stack trace: the frames come from the unwinder and the
 * symbol table of the executable, so there are method names but no line
 * numbers. LLVM may inline small methods, so only the outermost frame is
 * certain.
 */
class StackTraceE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void exceptionsKnowTheirStackTrace() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                public class Main {
                    static int depth = 3;

                    static void thrower(int n) {
                        if (n > 0) {
                            thrower(n - 1);
                            depth += n;
                        } else {
                            throw new IllegalStateException("boom");
                        }
                    }

                    public static void main(String[] args) {
                        try {
                            thrower(depth);
                        } catch (IllegalStateException e) {
                            StackTraceElement[] st = e.getStackTrace();
                            System.out.println(st.length > 0 ? "frames yes" : "frames no");
                            System.out.println("first " + st[0].getClassName() + "." + st[0].getMethodName()
                                    + " " + st[0].getFileName() + ":" + st[0].getLineNumber());
                            boolean main = false;
                            for (StackTraceElement el : st) {
                                if (el.getClassName().equals("Main") && el.getMethodName().equals("main")) main = true;
                            }
                            System.out.println("main " + main);
                            java.io.StringWriter w = new java.io.StringWriter();
                            e.printStackTrace(new java.io.PrintWriter(w));
                            String[] lines = w.toString().split("\\n");
                            System.out.println(lines[0].trim());
                            System.out.println(lines[1].trim().startsWith("at Main."));
                        }
                        throw new IllegalArgumentException("fatal");
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
        final String expected = "frames yes\nfirst Main.thrower Main.java:9\nmain true\n"
                + "java.lang.IllegalStateException: boom\ntrue\n";
        assertTrue(output.startsWith(expected), output);
        // The exception nobody catches: like the JVM, with the trace of the throw.
        final String uncaught = output.substring(expected.length());
        assertTrue(uncaught.startsWith("Exception in thread \"main\" java.lang.IllegalArgumentException: fatal\n"),
                uncaught);
        assertTrue(uncaught.contains("\tat Main.main(Main.java:32)"), uncaught);
        assertEquals(1, exit, output);
    }
}
