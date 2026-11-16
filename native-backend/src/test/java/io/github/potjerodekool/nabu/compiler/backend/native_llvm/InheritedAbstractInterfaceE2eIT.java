package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Method.getDefaultValue} of the elements of an annotation type from a library (bytecode), asked by library
 * code (Spring's AnnotationTypeMapping checks that aliased attributes declare default values).
 */
class InheritedAbstractInterfaceE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void abstractClassCallsInheritedInterfaceMethod() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path libSrc = tempDir.resolve("libsrc/lib");
        Files.createDirectories(libSrc);
        Files.writeString(libSrc.resolve("Ext.java"), """
                package lib;
                public interface Ext {
                    boolean isEnabled(String level, String message);
                    boolean isEnabled(String level, String message, Object... params);
                    boolean isEnabled(String level, String message, Object p0);
                }
                """);
        Files.writeString(libSrc.resolve("Abs.java"), """
                package lib;
                public abstract class Abs implements Ext {
                    public String logIf(String level, String message, Object... params) {
                        StringBuilder sb = new StringBuilder();
                        if (isEnabled(level, message)) sb.append("a");
                        if (isEnabled(level, message, params)) sb.append("b");
                        if (isEnabled(level, message, params[0])) sb.append("c");
                        return sb.toString();
                    }
                }
                """);
        Files.writeString(libSrc.resolve("Impl.java"), """
                package lib;
                public class Impl extends Abs {
                    public boolean isEnabled(String level, String message) { return true; }
                    public boolean isEnabled(String level, String message, Object... params) { return params.length == 1; }
                    public boolean isEnabled(String level, String message, Object p0) { return false; }
                }
                """);
        final Path libClasses = tempDir.resolve("libclasses");
        Files.createDirectories(libClasses);
        final int javac = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", libClasses.toString(),
                libSrc.resolve("Ext.java").toString(), libSrc.resolve("Abs.java").toString(), libSrc.resolve("Impl.java").toString());
        assertEquals(0, javac);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        lib.Abs logger = new lib.Impl();
                        System.out.println(logger.logIf("warn", "m", "x"));
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [ ]
                """);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .option(CompilerOption.CLASS_PATH, libClasses.toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.GC, "BOEHM")
                .option(CompilerOption.SYSTEM, new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();
        final var diagnostics = new java.util.ArrayList<String>();
        final var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        assertEquals(0, compiler.compile(options), String.join("\n", diagnostics));
        final Path exe;
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
                    Files.copy(ll, Path.of(keep.substring(0, keep.length() - ".exe".length()) + ".ll"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        final Path log = tempDir.resolve("run.log");
        final Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("programma hangt; output tot nu toe:\n"
                    + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
        final int exit = proc.exitValue();
        assertEquals("ab\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
