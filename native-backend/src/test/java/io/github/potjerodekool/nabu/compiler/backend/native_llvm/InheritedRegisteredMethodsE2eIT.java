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
class InheritedRegisteredMethodsE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void getMethodsIncludesRegisteredSuperclassMethods() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                import java.lang.reflect.Method;

                public class Main {
                    public static class Base {
                        public int order;
                        public void setOrder(int order) { this.order = order; }
                        public int getOrder() { return order; }
                    }
                    public static class Child extends Base {
                        public Child() { }
                    }
                    public static void main(String[] args) throws Exception {
                        int found = 0;
                        for (Method m : Child.class.getMethods()) {
                            if (m.getName().equals("setOrder")) {
                                found++;
                                System.out.println(m.getName() + " " + m.getDeclaringClass().getSimpleName());
                            }
                        }
                        Method set = Child.class.getMethod("setOrder", int.class);
                        Child child = new Child();
                        set.invoke(child, 5);
                        System.out.println(found + " " + child.order);
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [
                  { "name": "app.Main$Child", "methods": [ { "name": "<init>", "parameterTypes": [] } ] },
                  { "name": "app.Main$Base", "methods": [ { "name": "setOrder", "parameterTypes": ["int"] } ] }
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
                setOrder Base
                1 5
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
