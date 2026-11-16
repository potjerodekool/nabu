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
 * Method and field reflection natively, for the classes of a
 * {@code reflect-config.json}: {@code getMethods} (inherited, overrides
 * hidden), {@code getDeclaredMethods}, {@code getMethod} and
 * {@code getDeclaredMethod} (with NoSuchMethodException), {@code Method.invoke}
 * of instance (virtual), static and private methods with the target's
 * exception wrapped, the modifiers and {@code toString}; {@code getFields}
 * and {@code getField} with the inherited public fields of
 * {@code allPublicFields}; static fields; generic types of fields and
 * methods; method and parameter annotations; declared exceptions;
 * constructors with arguments (and InstantiationException for an abstract
 * class).
 */
class AnnotationTypeReflectionE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void annotationTypeMethodsAreVisible() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                import java.lang.reflect.Method;
                import java.util.ArrayList;
                import java.util.Collections;
                import java.util.List;

                public class Main {
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Order { int value() default 7; }
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Meta { String name() default "m"; }
                    @Meta(name = "meta")
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Outer { }
                    @Order(3) @Outer
                    public static class Child { }
                    public static void main(String[] args) throws Exception {
                        Order order = Child.class.getAnnotation(Order.class);
                        List<String> names = new ArrayList<>();
                        for (Method m : Order.class.getDeclaredMethods()) {
                            names.add(m.getName() + ":" + m.getParameterCount() + ":" + m.getReturnType().getName()
                                    + ":" + m.invoke(order));
                        }
                        Collections.sort(names);
                        System.out.println(names);
                        Meta meta = Outer.class.getAnnotation(Meta.class);
                        System.out.println(Meta.class.getDeclaredMethods().length + " " + (meta == null ? "null" : meta.name()));
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [
                  { "name": "app.Main$Child" }
                ]
                """);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
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
                    Files.copy(ll, Path.of(keep.substring(0, keep.length() - ".exe".length()) + ".ll"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
        assertEquals("""
                [value:0:int:3]
                1 meta
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
