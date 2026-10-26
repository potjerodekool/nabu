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
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Cmd { String value(); Level level() default Level.LOW; }
                    public enum Level { LOW, HIGH }
                    public static class Box<T extends Number> {
                        public T item;
                        public Box(java.util.List<T> items) { }
                    }
                    public abstract static class Shape {
                        public Shape() { }
                    }
                    public static class Child extends Base {
                        public String name = "c";
                        public static int counter = 5;
                        public java.util.List<String> tags = new ArrayList<>();
                        public Child() { }
                        public Child(String name, int times) { this.name = name.repeat(times); }
                        @Cmd(value = "greet", level = Level.HIGH)
                        public String hello(@Cmd("who") String name) { return "child " + name; }
                        public <T extends Comparable<T>> List<T> sorted(java.util.Set<? extends T> in) { return new ArrayList<>(in); }
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
                        Field counter = c.getField("counter");
                        counter.setInt(null, counter.getInt(null) + 1);
                        System.out.println("static " + Child.counter + " " + Modifier.isStatic(counter.getModifiers()));
                        java.lang.reflect.Type tagsType = c.getField("tags").getGenericType();
                        java.lang.reflect.ParameterizedType tagsParameterized = (java.lang.reflect.ParameterizedType) tagsType;
                        Method sorted = c.getMethod("sorted", java.util.Set.class);
                        System.out.println(tagsType + " " + (tagsParameterized.getActualTypeArguments()[0] == String.class)
                                + " " + (tagsParameterized.getRawType() == List.class) + " | " + sorted.getGenericReturnType()
                                + " " + sorted.getGenericParameterTypes()[0] + " " + sorted.getTypeParameters()[0].getName());
                        Cmd cmd = hello.getAnnotation(Cmd.class);
                        Cmd who = (Cmd) hello.getParameterAnnotations()[0][0];
                        System.out.println(cmd.value() + " " + cmd.level() + " " + (who.level() == Level.LOW)
                                + " " + who.value() + " " + hello.isAnnotationPresent(Cmd.class)
                                + " " + twice.getAnnotations().length + " " + hello.getDeclaredAnnotations().length);
                        Method fail = c.getMethod("fail");
                        System.out.println(fail.getExceptionTypes()[0].getName() + " | " + fail);
                        java.lang.reflect.Constructor<?> two = c.getConstructor(String.class, int.class);
                        Child made = (Child) two.newInstance("ab", 2);
                        System.out.println(made.name + " " + c.getDeclaredConstructors().length + " " + two
                                + " " + ((Child) c.getDeclaredConstructor().newInstance()).name);
                        java.lang.reflect.TypeVariable<?> item = (java.lang.reflect.TypeVariable<?>) Box.class.getField("item").getGenericType();
                        System.out.println(item + " " + item.getBounds()[0] + " " + Box.class.getTypeParameters()[0].getName()
                                + " " + Box.class.getConstructor(List.class).getGenericParameterTypes()[0]);
                        try {
                            Shape.class.getDeclaredConstructor().newInstance();
                            System.out.println("abstract made");
                        } catch (InstantiationException e) {
                            System.out.println("abstract " + e.getMessage());
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        final Path out = tempDir.resolve("out");
        final Path config = out.resolve("META-INF/native-image/test");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reflect-config.json"), """
                [
                  { "name": "app.Main$Child", "allPublicFields": true, "allDeclaredMethods": true, "allPublicMethods": true,
                    "allDeclaredConstructors": true },
                  { "name": "app.Main$Base", "allPublicMethods": true },
                  { "name": "app.Main$Shape", "allDeclaredConstructors": true },
                  { "name": "app.Main$Box", "allPublicFields": true, "allPublicConstructors": true }
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
                name counter tags baseCount | Base true 1
                no field hidden
                fail,hello,secret,sorted,twice | fail,hello,sorted,twice | inherited
                child x child y inherited 42 s
                true true true public static int app.Main$Child.twice(int)
                target io
                no method app.Main$Child.secret()
                static 6 true
                java.util.List<java.lang.String> true true | java.util.List<T> java.util.Set<? extends T> T
                greet HIGH true who true 0 1
                java.io.IOException | public void app.Main$Child.fail() throws java.io.IOException
                abab 2 public app.Main$Child(java.lang.String,int) c
                T class java.lang.Number T java.util.List<T>
                abstract app.Main$Shape
                """, output, "exit=" + exit);
        assertEquals(0, exit, output);
    }
}
