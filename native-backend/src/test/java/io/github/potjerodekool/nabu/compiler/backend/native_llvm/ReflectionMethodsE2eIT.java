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
 * {@code allPublicFields}.
 */
class ReflectionMethodsE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void configuredMethodsAndFields() throws Exception {
        NativeToolchain.assumeGcc();
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) root = root.getParentFile();
        final Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("app"));
        Files.writeString(srcDir.resolve("app/Main.java"), """
                package app;

                import java.lang.reflect.Field;
                import java.lang.reflect.InvocationTargetException;
                import java.lang.reflect.Method;
                import java.lang.reflect.Modifier;
                import java.util.ArrayList;
                import java.util.Collections;
                import java.util.List;

                public class Main {
                    public static class Base {
                        public int baseCount = 1;
                        private int hidden = 2;
                        public String hello(String name) { return "base " + name; }
                        public String inherited() { return "inherited"; }
                    }
                    public static class Child extends Base {
                        public String name = "c";
                        public String hello(String name) { return "child " + name; }
                        public static int twice(int x) { return x * 2; }
                        private String secret() { return "s"; }
                        public void fail() throws java.io.IOException { throw new java.io.IOException("io"); }
                    }
                    static String names(Method[] methods, Class<?> declaring) {
                        List<String> names = new ArrayList<>();
                        for (Method m : methods) {
                            if (declaring == null || m.getDeclaringClass() == declaring) names.add(m.getName());
                        }
                        Collections.sort(names);
                        return String.join(",", names);
                    }
                    public static void main(String[] args) throws Exception {
                        Class<?> c = Child.class;
                        StringBuilder fields = new StringBuilder();
                        for (Field f : c.getFields()) fields.append(f.getName()).append(' ');
                        Field baseCount = c.getField("baseCount");
                        System.out.println(fields + "| " + baseCount.getDeclaringClass().getSimpleName()
                                + " " + Modifier.isPublic(baseCount.getModifiers()) + " " + baseCount.getInt(new Child()));
                        try {
                            c.getField("hidden");
                            System.out.println("found hidden");
                        } catch (NoSuchFieldException e) {
                            System.out.println("no field " + e.getMessage());
                        }
                        System.out.println(names(c.getDeclaredMethods(), null) + " | " + names(c.getMethods(), c)
                                + " | " + names(c.getMethods(), Base.class));
                        Method hello = c.getMethod("hello", String.class);
                        Method baseHello = Base.class.getMethod("hello", String.class);
                        Method twice = c.getMethod("twice", int.class);
                        Method secret = c.getDeclaredMethod("secret");
                        secret.setAccessible(true);
                        System.out.println(hello.invoke(new Child(), "x") + " " + baseHello.invoke(new Child(), "y")
                                + " " + c.getMethod("inherited").invoke(new Child()) + " " + twice.invoke(null, 21)
                                + " " + secret.invoke(new Child()));
                        System.out.println(Modifier.isStatic(twice.getModifiers()) + " " + Modifier.isPrivate(secret.getModifiers())
                                + " " + (hello == c.getMethod("hello", String.class)) + " " + twice);
                        try {
                            c.getMethod("fail").invoke(new Child());
                        } catch (InvocationTargetException e) {
                            System.out.println("target " + e.getCause().getMessage());
                        }
                        try {
                            c.getMethod("secret");
                            System.out.println("found secret");
                        } catch (NoSuchMethodException e) {
                            System.out.println("no method " + e.getMessage());
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [
                  { "name": "app.Main$Child", "allPublicFields": true, "allDeclaredMethods": true, "allPublicMethods": true },
                  { "name": "app.Main$Base", "allPublicMethods": true }
                ]
                """);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());
        final var options = new CompilerOptions.CompilerOptionsBuilder()
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
            exe = walk.filter(p -> p.getFileName().toString().endsWith(".exe")).findFirst().orElseThrow();
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
                name baseCount | Base true 1
                no field hidden
                fail,hello,secret,twice | fail,hello,twice | inherited
                child x child y inherited 42 s
                true true true public static int app.Main$Child.twice(int)
                target io
                no method app.Main$Child.secret()
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
