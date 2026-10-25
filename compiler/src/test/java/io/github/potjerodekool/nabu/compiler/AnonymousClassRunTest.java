package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Anonieme klassen (JLS §15.9.5) via de ASM-backend: een interface-implementatie
 * in een veld-initializer die een veld van de omsluitende instantie leest, en een
 * anonieme subklasse met super-constructorargumenten die een parameter vangt.
 */
class AnonymousClassRunTest {

    @TempDir
    Path tempDir;

    private Class<?> compileAndLoad(final String className, final String code) throws Exception {
        final var srcDir = tempDir.resolve("src");
        final var javaFile = srcDir.resolve("mini/" + className + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, code);

        final var outDir = tempDir.resolve("out");
        final var rootPath = "C:\\projects\\nabu\\compiler";
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();

        final var compiler = new NabuCompiler();
        final var errors = new AtomicInteger();
        final var messages = new java.util.ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                errors.incrementAndGet();
                messages.add(d.getMessage(java.util.Locale.ROOT).toString());
            }
        });
        final var rc = compiler.compile(options);
        assertEquals(0, errors.get(), String.join("\n", messages));
        assertEquals(0, rc, String.join("\n", messages));

        final var loader = new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()},
                getClass().getClassLoader());
        return loader.loadClass("mini." + className);
    }

    @Test
    void anonymousClassesImplementInterfaceAndExtendClass() throws Exception {
        final var cls = compileAndLoad("Outer", """
                package mini;
                public class Outer {
                    public interface Handler { int handle(int x); }
                    public static abstract class Base {
                        int k;
                        Base(int k) { this.k = k; }
                        abstract int get();
                    }
                    int field = 5;
                    Handler h = new Handler() {
                        public int handle(int x) { return x + field; }
                    };
                    public int run(final int add) {
                        Base b = new Base(7) {
                            int get() { return k + add; }
                        };
                        return b.get() + h.handle(1);
                    }
                    public static int start() {
                        return new Outer().run(100);
                    }
                }
                """);
        assertEquals(113, cls.getMethod("start").invoke(null));
    }

    /** An anonymous class of a qualified nested JDK interface: {@code new Thread.UncaughtExceptionHandler() {..}}. */
    @Test
    void anonymousClassOfQualifiedNestedInterface() throws Exception {
        final var cls = compileAndLoad("Nested", """
                package mini;
                public class Nested {
                    public static int start() {
                        final int[] seen = new int[1];
                        java.util.Map.Entry<String, String> e = null;
                        Thread.State st = null;
                        Thread.UncaughtExceptionHandler h = new Thread.UncaughtExceptionHandler() {
                            public void uncaughtException(Thread t, Throwable e) { seen[0] = 42; }
                        };
                        h.uncaughtException(null, null);
                        return seen[0];
                    }
                }
                """);
        assertEquals(42, cls.getMethod("start").invoke(null));
    }

    /** An anonymous class in the body of a try-with-resources statement. */
    @Test
    void anonymousClassInTryWithResources() throws Exception {
        final var cls = compileAndLoad("TryRes", """
                package mini;
                import java.util.concurrent.Callable;
                public class TryRes {
                    public static int start() throws Exception {
                        try (java.io.StringReader reader = new java.io.StringReader("x")) {
                            Callable<Integer> a = new Callable<Integer>() {
                                public Integer call() { return 6; }
                            };
                            Callable<Integer> b = new Callable<Integer>() {
                                public Integer call() { return 7; }
                            };
                            return a.call() * 10 + b.call();
                        }
                    }
                }
                """);
        assertEquals(67, cls.getMethod("start").invoke(null));
    }

    @Test
    void anonymousClassInPlainBlock() throws Exception {
        final var cls = compileAndLoad("BlockAnon", """
                package mini;
                import java.util.concurrent.Callable;
                public class BlockAnon {
                    public static int start() throws Exception {
                        {
                            Callable<Integer> a = new Callable<Integer>() {
                                public Integer call() { return 6; }
                            };
                            return a.call();
                        }
                    }
                }
                """);
        assertEquals(6, cls.getMethod("start").invoke(null));
    }

    @Test
    void anonymousClassInTryCatchFinally() throws Exception {
        final var cls = compileAndLoad("TryCatchAnon", """
                package mini;
                import java.util.concurrent.Callable;
                public class TryCatchAnon {
                    public static int start() throws Exception {
                        Throwable p = null;
                        try {
                            Callable<Integer> a = new Callable<Integer>() {
                                public Integer call() { return 6; }
                            };
                            return a.call();
                        } catch (Throwable t) { p = t; throw t; }
                        finally { if (p != null) { System.out.println("x"); } }
                    }
                }
                """);
        assertEquals(6, cls.getMethod("start").invoke(null));
    }

    @Test
    void anonymousClassInStaticMethodCapturesLocal() throws Exception {
        final var cls = compileAndLoad("Statics", """
                package mini;
                import java.util.function.IntSupplier;
                public class Statics {
                    public static int start() {
                        final int base = 40;
                        Runnable r = new Runnable() { public void run() { } };
                        r.run();
                        IntSupplier s = new IntSupplier() {
                            public int getAsInt() { return base + 2; }
                        };
                        return s.getAsInt();
                    }
                }
                """);
        assertEquals(42, cls.getMethod("start").invoke(null));
    }

    /**
     * Een niet-literal veld-initializer draait één keer, ná de super-aanroep.
     * TypeEnter zet hem al als statement in de constructor; de IR-generatie
     * herkende de impliciete {@code this.super()} niet en emitteerde hem
     * daarnaast nog eens vóór de super-aanroep.
     */
    @Test
    void nonLiteralFieldInitializerRunsOnce() throws Exception {
        final var cls = compileAndLoad("Once", """
                package mini;
                public class Once {
                    static int calls;
                    static int next() { calls++; return calls; }
                    int a = 5;
                    int b = next();
                    public static int start() {
                        Once o = new Once();
                        return calls * 100 + o.a * 10 + o.b;
                    }
                }
                """);
        assertEquals(151, cls.getMethod("start").invoke(null));
    }

    /**
     * Gevangen variabelen die alleen binnen try/catch/finally of een
     * array-toegang voorkomen (zoals picocli's detectTerminalWidth-Runnable).
     * De capture-analyse daalde daar niet in af, waardoor de ontvanger van
     * {@code size.set(..)} ontbrak en ASM's frame-berekening crashte.
     */
    @Test
    void capturesUsedInsideTryCatchFinally() throws Exception {
        final var cls = compileAndLoad("Tf", """
                package mini;
                import java.util.concurrent.atomic.AtomicInteger;
                import java.io.BufferedReader;
                import java.io.StringReader;
                public class Tf {
                    static void close(Object o) { }
                    public static int start() {
                        final AtomicInteger size = new AtomicInteger(-1);
                        final String[] cmd = new String[] {"a", "b"};
                        Runnable r = new Runnable() {
                            public void run() {
                                Object proc = null;
                                BufferedReader reader = null;
                                try {
                                    reader = new BufferedReader(new StringReader(String.join(System.lineSeparator(), cmd)));
                                    String txt = "";
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        txt += " " + line;
                                    }
                                    size.set(txt.length());
                                } catch (Exception ignored) {
                                    size.set(-2);
                                } finally {
                                    if (proc != null) { proc.hashCode(); }
                                    close(reader);
                                }
                            }
                        };
                        r.run();
                        return size.get();
                    }
                }
                """);
        assertEquals(4, cls.getMethod("start").invoke(null));
    }
    /**
     * Een {@code String[]} voor een {@code CharSequence...}-parameter is de
     * array zelf (JLS §15.12.4.2); de ASM-backend pakte hem opnieuw in
     * (ArrayStoreException).
     */
    @Test
    void subtypeArrayPassedToVarArgsIsNotWrapped() throws Exception {
        final var cls = compileAndLoad("Tn", """
                package mini;
                import java.util.concurrent.atomic.AtomicInteger;
                import java.io.BufferedReader;
                import java.io.StringReader;
                public class Tn {
                    static void close(Object o) { }
                    static class R implements Runnable {
                        AtomicInteger size; String[] cmd;
                        R(AtomicInteger size, String[] cmd) { this.size = size; this.cmd = cmd; }
                            public void run() {
                                Object proc = null;
                                BufferedReader reader = null;
                                try {
                                    reader = new BufferedReader(new StringReader(String.join(System.lineSeparator(), cmd)));
                                    String txt = "";
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        txt += " " + line;
                                    }
                                    size.set(txt.length());
                                } catch (Exception ignored) {
                                    size.set(-2);
                                } finally {
                                    if (proc != null) { proc.hashCode(); }
                                    close(reader);
                                }
                            }
                    }
                    public static int start() {
                        final AtomicInteger size = new AtomicInteger(-1);
                        new R(size, new String[] {"a", "b"}).run();
                        return size.get();
                    }
                }
                """);
        assertEquals(4, cls.getMethod("start").invoke(null));
    }

    /**
     * Picocli's Optional-converter: {@code wrap(c.convert(v))} met
     * {@code Conv<?> c} (een wildcard-argument werd door de resolver
     * verworpen, waarna de aanroep stil verdween), en een anonieme
     * {@code Conv<String>} met {@code String convert(String)} die een
     * return-bridge {@code Object convert(String)} nodig heeft.
     */
    @Test
    void wildcardArgumentAndReturnTypeBridge() throws Exception {
        final var cls = compileAndLoad("Wc", """
                package mini;
                public class Wc {
                    public interface Conv<T> { T convert(String s) throws Exception; }
                    static Object wrap(Object o) { return "w" + o; }
                    static Conv<?> make(final Conv<?> c) {
                        return new Conv<Object>() {
                            public Object convert(String v) throws Exception {
                                return v == null ? null : wrap(c.convert(v));
                            }
                        };
                    }
                    public static Object start() throws Exception {
                        Conv<String> id = new Conv<String>() {
                            public String convert(String s) { return s + "!"; }
                        };
                        return make(id).convert("x");
                    }
                }
                """);
        assertEquals("wx!", cls.getMethod("start").invoke(null));
    }
}
