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
 * {@code super.name()} in a class that reaches the abstract declaration of an interface on a shorter path than the
 * default method of a sub-interface (JVMS 5.4.3.3: the maximally specific default wins). Hibernate's
 * ManyToManyCollectionPart.findSubPart ended in the abstract declaration, a function the program did not have, and the
 * request hung.
 */
class MaximallySpecificDefaultE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void superCallReachesTheMostSpecificDefault() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        final Path libSrc = tempDir.resolve("libsrc/lib");
        Files.createDirectories(libSrc);
        Files.writeString(libSrc.resolve("Base.java"), """
                package lib;
                public interface Base {
                    String name();
                }
                """);
        Files.writeString(libSrc.resolve("Other.java"), """
                package lib;
                public interface Other extends Base {
                }
                """);
        Files.writeString(libSrc.resolve("Mid.java"), """
                package lib;
                public interface Mid extends Base {
                    @Override
                    default String name() { return "mid"; }
                }
                """);
        Files.writeString(libSrc.resolve("Mid2.java"), """
                package lib;
                public interface Mid2 extends Mid {
                }
                """);
        Files.writeString(libSrc.resolve("Abs.java"), """
                package lib;
                public abstract class Abs implements Other, Mid2 {
                }
                """);
        Files.writeString(libSrc.resolve("Sub.java"), """
                package lib;
                public class Sub extends Abs {
                    @Override
                    public String name() { return "sub:" + super.name(); }
                }
                """);
        final Path libClasses = tempDir.resolve("libclasses");
        Files.createDirectories(libClasses);
        final String[] names = {"Base", "Other", "Mid", "Mid2", "Abs", "Sub"};
        final java.util.List<String> javacArgs = new java.util.ArrayList<>(java.util.List.of("-d", libClasses.toString()));
        for (final String name : names) {
            javacArgs.add(libSrc.resolve(name + ".java").toString());
        }
        final int javac = ToolProvider.getSystemJavaCompiler().run(null, null, null, javacArgs.toArray(new String[0]));
        assertEquals(0, javac);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        lib.Base base = new lib.Sub();
                        System.out.println(base.name());
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
        assertEquals("sub:mid\n", output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
