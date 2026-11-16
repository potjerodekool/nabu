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
 * Kopieconstructors van JDK-collecties uit bytecode: picocli's
 * {@code CommandSpec.addMixin} kopieert {@code mixin.options()} (een
 * unmodifiableList) naar een {@code LinkedHashSet} en itereert daarover; native
 * bleef die set leeg, waardoor --help/--version nooit als optie werden toegevoegd
 * (hasNext() staat in de abstracte LinkedHashIterator, die Iterator niet zelf
 * implementeert). Daarnaast: een object-operand in een string-concat werd "".
 */
class RuntimeSuperclassVtableE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void objectSlotsOfClassLoaderSubclass() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                import java.util.*;
                public class Main {
                    static final class Loader extends ClassLoader {
                        Loader() { super("app", null); }
                        @Override public String toString() { return "loader"; }
                    }
                    public static void main(String[] args) throws Exception {
                        Object l = new Loader();
                        Map<Object, String> m = new Hashtable<>();
                        m.put(l, "x");
                        System.out.println(m.get(l) + " " + l.equals(l) + " " + (l.hashCode() == l.hashCode()) + " " + l);
                    }
                }
                """, StandardCharsets.UTF_8);
        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
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
        final ProcessBuilder builder = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("NABU_ENV_TEST", "hello");
        Process proc = builder.start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("programma hangt; output tot nu toe:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int exit = proc.exitValue();
        assertEquals("x true true loader\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
