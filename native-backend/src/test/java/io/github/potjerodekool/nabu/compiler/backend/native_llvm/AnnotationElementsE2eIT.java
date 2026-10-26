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
 * Annotation elements natively, for an annotation type compiled from source
 * and one from a library (bytecode): arrays of primitives, arrays of enums,
 * a nested annotation and an array of nested annotations, with defaults.
 */
class AnnotationElementsE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void elementsOfEveryKind() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();

        // The library, compiled by javac: its annotations reach Nabu as bytecode.
        final Path libSrc = tempDir.resolve("libsrc/lib");
        Files.createDirectories(libSrc);
        Files.writeString(libSrc.resolve("LibLevel.java"), "package lib; public enum LibLevel { LOW, HIGH }");
        Files.writeString(libSrc.resolve("LibTag.java"), """
                package lib;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface LibTag { String value(); }
                """);
        Files.writeString(libSrc.resolve("LibSpec.java"), """
                package lib;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface LibSpec {
                    int[] numbers() default {1, 2};
                    LibLevel[] levels() default {};
                    LibTag tag();
                    LibTag[] tags() default {};
                    boolean[] flags() default {true};
                    char[] chars() default {'x'};
                }
                """);
        Files.writeString(libSrc.resolve("LibTarget.java"), """
                package lib;
                @LibSpec(numbers = {3, 4, 5}, levels = {LibLevel.HIGH, LibLevel.LOW}, tag = @LibTag("t"),
                         tags = {@LibTag("a"), @LibTag("b")})
                public class LibTarget { }
                """);
        final Path libClasses = tempDir.resolve("libclasses");
        Files.createDirectories(libClasses);
        final int javac = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", libClasses.toString(),
                libSrc.resolve("LibLevel.java").toString(), libSrc.resolve("LibTag.java").toString(),
                libSrc.resolve("LibSpec.java").toString(), libSrc.resolve("LibTarget.java").toString());
        assertEquals(0, javac);

        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.util.Arrays;

                public class Main {
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Tag { String value(); }
                    public enum Level { LOW, HIGH }
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Spec {
                        int[] numbers() default {1, 2};
                        Level[] levels() default {};
                        Tag tag();
                        Tag[] tags() default {};
                        boolean[] flags() default {true};
                        char[] chars() default {'x'};
                    }
                    @Spec(numbers = {3, 4, 5}, levels = {Level.HIGH, Level.LOW}, tag = @Tag("t"), tags = {@Tag("a"), @Tag("b")})
                    public static class Target { }

                    public static void main(String[] args) {
                        Spec s = Target.class.getAnnotation(Spec.class);
                        System.out.println(Arrays.toString(s.numbers()) + " " + Arrays.toString(s.levels()) + " "
                                + (s.levels()[0] == Level.HIGH) + " " + s.tag().value() + " " + s.tags().length
                                + s.tags()[1].value() + " " + Arrays.toString(s.flags()) + " " + Arrays.toString(s.chars()));
                        lib.LibSpec l = lib.LibTarget.class.getAnnotation(lib.LibSpec.class);
                        System.out.println(Arrays.toString(l.numbers()) + " " + Arrays.toString(l.levels()) + " "
                                + (l.levels()[0] == lib.LibLevel.HIGH) + " " + l.tag().value() + " " + l.tags().length
                                + l.tags()[1].value() + " " + Arrays.toString(l.flags()) + " " + Arrays.toString(l.chars()));
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [ { "name": "app.Main$Target" }, { "name": "lib.LibTarget" } ]
                """);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
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
        assertEquals("""
                [3, 4, 5] [HIGH, LOW] true t 2b [true] [x]
                [3, 4, 5] [HIGH, LOW] true t 2b [true] [x]
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
