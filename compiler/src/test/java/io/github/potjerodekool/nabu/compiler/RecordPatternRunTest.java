package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Record patterns in instanceof, lowered to a type check and assignments of the bindings.
 */
// The classes are compiled for release 25 and loaded in this JVM.
@EnabledForJreRange(min = JRE.JAVA_25)
class RecordPatternRunTest {

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
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SYSTEM, rootPath + "\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .option(CompilerOption.RELEASE, "25")
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
    void recordPatternInIf() throws Exception {
        final var cls = compileAndLoad("Rp", """
                package mini;
                public class Rp {
                    record Point(int x, int y) {}
                    record Line(Point from, Point to) {}
                    record Named(String name, Object value) {}
                    static int sum(Object o) {
                        if (o instanceof Point(int x, int y)) {
                            return x + y;
                        }
                        return -1;
                    }
                    static int twice(Object o, Object p) {
                        int r = 0;
                        if (o instanceof Point(int x, int y)) { r += x * y; }
                        if (p instanceof Point(int x, int y)) { r += x - y; }
                        return r;
                    }
                    static int length(Object o) {
                        if (o instanceof Line(Point(int a, int b), Point(int c, int d))) {
                            return (c - a) + (d - b);
                        }
                        return -1;
                    }
                    static String name(Object o) {
                        if (o instanceof Named(String n, Object v) && n.length() > 1) {
                            return n + "=" + v;
                        } else {
                            return "none";
                        }
                    }
                    public static String run() {
                        return sum(new Point(3, 4)) + " " + sum("x") + " " + twice(new Point(2, 5), new Point(9, 4)) + " "
                                + length(new Line(new Point(1, 1), new Point(4, 5))) + " "
                                + name(new Named("ab", 7)) + " " + name(new Named("a", 7)) + " " + name(null);
                    }
                }
                """);
        assertEquals("7 -1 15 7 ab=7 none none", cls.getMethod("run").invoke(null));
    }

    @Test
    void unnamedVariablesAndPatterns() throws Exception {
        final var cls = compileAndLoad("Un", """
                package mini;
                public class Un {
                    record Point(int x, int y) {}
                    static int second(Object o) {
                        if (o instanceof Point(var _, int y)) {
                            return y;
                        }
                        if (o instanceof Point(_, _)) {
                            return -2;
                        }
                        return -1;
                    }
                    public static String run() {
                        int _ = 5;
                        int _ = 6;
                        java.util.function.BiFunction<Integer, Integer, Integer> f = (_, b) -> b + 1;
                        String r = "";
                        try {
                            Integer.parseInt("x");
                        } catch (NumberFormatException _) {
                            r = "nfe";
                        }
                        return second(new Point(1, 9)) + " " + second("s") + " " + f.apply(1, 2) + " " + r;
                    }
                }
                """);
        assertEquals("9 -1 3 nfe", cls.getMethod("run").invoke(null));
    }

    @Test
    void statementsBeforeSuperCall() throws Exception {
        final var cls = compileAndLoad("Flex", """
                package mini;
                public class Flex {
                    static class Base {
                        final int value;
                        Base(int value) { this.value = value; }
                    }
                    static class Sub extends Base {
                        Sub(int x) {
                            if (x < 0) {
                                throw new IllegalArgumentException("negative");
                            }
                            int doubled = x * 2;
                            super(doubled + 1);
                        }
                    }
                    public static String run() {
                        String r = "" + new Sub(4).value;
                        try {
                            new Sub(-1);
                        } catch (IllegalArgumentException e) {
                            r += " " + e.getMessage();
                        }
                        return r;
                    }
                }
                """);
        assertEquals("9 negative", cls.getMethod("run").invoke(null));
    }

    @Test
    void recordPatternInReturnWhileAndLaterOperand() throws Exception {
        final var cls = compileAndLoad("Rp2", """
                package mini;
                public class Rp2 {
                    record Point(int x, int y) {}
                    record Box(Object content) {}
                    static boolean positive(Object o) {
                        return o instanceof Point(int x, int y) && x > 0 && y > 0;
                    }
                    static String later(Object a, Object b) {
                        if (a != null && b instanceof Point(int x, int y) && x < y) {
                            return "lt" + x + y;
                        }
                        return "no";
                    }
                    static String two(Object a, Object b) {
                        if (a instanceof Point(int x, int y) && b instanceof Point(int u, int v)) {
                            return "" + (x + y + u + v);
                        }
                        return "no";
                    }
                    static int unwrap(Object o) {
                        int depth = 0;
                        while (o instanceof Box(Object inner)) {
                            depth++;
                            o = inner;
                        }
                        return depth;
                    }
                    static int guardOrder(Object a) {
                        int calls = 0;
                        if (a instanceof Point(int x, int y) && x > 0) { calls = x + y; }
                        return calls;
                    }
                    public static String run() {
                        return positive(new Point(1, 2)) + " " + positive(new Point(-1, 2)) + " " + positive("s") + " "
                                + later("a", new Point(1, 2)) + " " + later(null, new Point(1, 2)) + " " + later("a", "b") + " "
                                + two(new Point(1, 2), new Point(3, 4)) + " " + two(new Point(1, 2), "x") + " "
                                + unwrap(new Box(new Box(new Box("x")))) + " " + unwrap("x") + " " + guardOrder(new Point(2, 3));
                    }
                }
                """);
        assertEquals("true false false lt12 no no 10 no 3 0 5", cls.getMethod("run").invoke(null));
    }

    @Test
    void moduleImport() throws Exception {
        final var cls = compileAndLoad("Mi", """
                package mini;
                import module java.base;
                import module java.sql;
                public class Mi {
                    public static String run() {
                        List<String> names = new ArrayList<>(List.of("b", "a"));
                        Collections.sort(names);
                        Path path = Path.of("x", "y");
                        Time date = new Time(0L);
                        Logger logger = Logger.getLogger("mi");
                        return names + " " + path.getNameCount() + " " + date.getTime() + " " + logger.getName();
                    }
                }
                """);
        assertEquals("[a, b] 2 0 mi", cls.getMethod("run").invoke(null));
    }

    @Test
    void negatedRecordPatternKeepsItsBindingsAfterTheIf() throws Exception {
        final var cls = compileAndLoad("Neg", """
                package mini;
                public class Neg {
                    record Point(int x, int y) {}
                    static int sum(Object o) {
                        if (!(o instanceof Point(int x, int y))) {
                            return -1;
                        }
                        return x + y;
                    }
                    static int twice(Object a, Object b) {
                        if (!(a instanceof Point(int x, int y))) { return -1; }
                        if (!(b instanceof Point(int x2, int y2))) { return -2; }
                        return x + y + x2 + y2;
                    }
                    public static String run() {
                        return sum(new Point(3, 4)) + " " + sum("s") + " " + twice(new Point(1, 2), new Point(3, 4)) + " "
                                + twice(new Point(1, 2), "x") + " " + twice("x", "y");
                    }
                }
                """);
        assertEquals("7 -1 10 -2 -1", cls.getMethod("run").invoke(null));
    }

    @Test
    void recordPatternUnderOrAndInConditionalExpressions() throws Exception {
        final var cls = compileAndLoad("Ors", """
                package mini;
                public class Ors {
                    record Point(int x, int y) {}
                    static int calls = 0;
                    static boolean tick() { calls++; return false; }
                    static String or(Object o, boolean flag) {
                        if (flag || o instanceof Point(int x, int y) && x > 1) {
                            return "yes";
                        }
                        return "no";
                    }
                    static String orAfter(Object o) {
                        if (!(o instanceof Point(int x, int y)) || x > y) {
                            return "bad";
                        }
                        return "ok" + (x + y);
                    }
                    static int ternary(Object o) {
                        return o instanceof Point(int x, int y) ? x * 10 + y : -1;
                    }
                    static int nested(Object o, Object p) {
                        int r = o instanceof Point(int x, int y)
                                ? (p instanceof Point(int a, int b) ? x + y + a + b : x + y)
                                : 0;
                        return r;
                    }
                    static int assigned(Object o) {
                        int r = 5;
                        r = o instanceof Point(int x, int y) ? x - y : 0;
                        return r;
                    }
                    static String shortCircuit(Object o) {
                        calls = 0;
                        boolean b = true || o instanceof Point(int x, int y) && tick();
                        boolean c = o instanceof Point(int x, int y) || tick();
                        return b + " " + c + " " + calls;
                    }
                    public static String run() {
                        return or("s", true) + " " + or(new Point(2, 0), false) + " " + or(new Point(1, 0), false) + " "
                                + or("s", false) + " " + orAfter("s") + " " + orAfter(new Point(5, 1)) + " "
                                + orAfter(new Point(1, 2)) + " " + ternary(new Point(3, 4)) + " " + ternary("s") + " "
                                + nested(new Point(1, 2), new Point(3, 4)) + " " + nested(new Point(1, 2), "s") + " "
                                + nested("s", "s") + " " + assigned(new Point(9, 4)) + " " + assigned("s") + " "
                                + shortCircuit(new Point(1, 1)) + " " + shortCircuit("s");
                    }
                }
                """);
        assertEquals("yes yes no no bad bad ok3 34 -1 10 3 0 5 0 true true 0 true false 1",
                cls.getMethod("run").invoke(null));
    }
}
