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
 * Frontend bugs found while probing the LLVM backend (2026-10-18), run here
 * with the ASM backend: a lambda whose expression body is a primitive for an
 * {@code Object}-returning interface, a static method reference as an
 * argument, {@code return <primitive>} from an {@code Object} method, and a
 * call whose result type comes from a generic or nested-type method used as an
 * argument of {@code String.valueOf}.
 */
class FrontendRegressionRunTest {

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

    private Object run(final String body) throws Exception {
        return run("", body);
    }

    private Object run(final String members, final String body) throws Exception {
        final var cls = compileAndLoad("Probe", """
                package mini;
                public class Probe {
                    interface Task { Object run() throws Exception; }
                    static String p(String name, Task task) {
                        try {
                            return name + "=" + task.run();
                        } catch (Throwable t) {
                            return name + " threw " + t;
                        }
                    }
                    static Object helper() { return "h"; }
                    MEMBERS
                    public static String run() throws Exception {
                        BODY
                    }
                }
                """.replace("MEMBERS", members).replace("BODY", body));
        return cls.getMethod("run").invoke(null);
    }

    @Test
    void lambdaWithPrimitiveBodyForObjectInterface() throws Exception {
        assertEquals("a=true b=1", run("""
                return p("a", () -> java.util.List.of("a", "b").contains("b")) + " " + p("b", () -> "x".length());"""));
    }

    @Test
    void staticMethodReferenceAsArgument() throws Exception {
        assertEquals("c=h", run("return p(\"c\", Probe::helper);"));
    }

    @Test
    void methodReferenceForms() throws Exception {
        assertEquals("3 xx pre-fix [a, b] 7 r", run("""
                static String twice(String s) { return s + s; }
                static String hs() { return "r"; }
                static java.util.function.Supplier<String> ret() { return Probe::hs; }""", """
                java.util.function.Function<String, Integer> len = String::length;
                java.util.function.Function<String, String> tw = Probe::twice;
                java.util.function.Supplier<java.util.List<String>> mk = java.util.ArrayList::new;
                String prefix = "pre-";
                java.util.function.UnaryOperator<String> cat = prefix::concat;
                java.util.List<String> l = mk.get();
                l.add("b");
                l.add("a");
                l.sort(String::compareTo);
                java.util.function.BinaryOperator<Integer> max = Math::max;
                return len.apply("abc") + " " + tw.apply("x") + " " + cat.apply("fix") + " " + l
                        + " " + max.apply(3, 7) + " " + ret().get();"""));
    }

    @Test
    void returnPrimitiveFromObjectMethod() throws Exception {
        assertEquals("true 3 x", run("""
                static Object prim(int i) {
                    if (i == 0) return true;
                    return i + 1;
                }
                static Object chr() { return 'x'; }""",
                "return prim(0) + \" \" + prim(2) + \" \" + chr();"));
    }

    @Test
    void nestedTypeStaticCallAsArgument() throws Exception {
        assertEquals("BLOCKED", run("return String.valueOf(Thread.State.valueOf(\"BLOCKED\"));"));
    }

    @Test
    void genericCallResultAsArgument() throws Exception {
        assertEquals("[Ljava.lang.String; 2", run(
                "return String.valueOf(java.util.Arrays.copyOf(new String[]{\"x\"}, 2).getClass().getName()) + \" \" + java.util.Arrays.copyOf(new String[]{\"x\"}, 2).length;"));
    }

    @Test
    void reflectiveArrayAsArgument() throws Exception {
        assertEquals("[Ljava.lang.String;", run(
                "return String.valueOf(java.lang.reflect.Array.newInstance(String.class, 1).getClass().getName());"));
    }

    @Test
    void lambdaForSamInheritedFromGenericSuperinterface() throws Exception {
        assertEquals("7 X", run("""
                java.util.function.BinaryOperator<Integer> max = (a, b) -> Math.max(a, b);
                java.util.function.UnaryOperator<String> up = s -> s.toUpperCase();
                return max.apply(3, 7) + " " + up.apply("x");"""));
    }

    /** A call that does not resolve is an error, not silently dropped code. */
    @Test
    void unresolvableCallIsReported() {
        final var error = org.junit.jupiter.api.Assertions.assertThrows(org.opentest4j.AssertionFailedError.class,
                () -> run("return String.valueOf(new Object(), 1, 2, 3);"));
        org.junit.jupiter.api.Assertions.assertTrue(
                error.getMessage().contains("Failed to resolve method valueOf"), error.getMessage());
    }

    @Test
    void nestedLambda() throws Exception {
        assertEquals("42 6 !", run("""
                int base = 41;
                java.util.function.Supplier<java.util.function.IntSupplier> s = () -> () -> base + 1;
                java.util.function.Function<Integer, java.util.function.Function<Integer, Integer>> add = a -> b -> a + b;
                return s.get().getAsInt() + " " + add.apply(2).apply(4) + " " + p("x", () -> ((java.util.function.Supplier<String>) () -> "!").get()).substring(2);"""));
    }

    /** Two wrappers: unboxed for arithmetic, compared as references by ==. */
    @Test
    void boxedArithmetic() throws Exception {
        assertEquals("6 6 true 2.5", run("""
                Integer x = 2;
                Integer y = 4;
                int z = x + y;
                Double d = 5.0;
                Double two = 2.0;
                return z + " " + (x + y) + " " + (x < y) + " " + (d / two);"""));
    }

    /**
     * `new int[2][3]` had type int[] and allocated only the outer array, and
     * nested initializers ({{1}, {2, 3}}) were flattened into one array.
     */
    @Test
    void multiDimensionalArray() throws Exception {
        assertEquals("2 3 5 2 null 3 2 4 0", run("""
                int[][] m = new int[2][3];
                m[1][2] = 5;
                String[][] partial = new String[2][];
                int[][] literal = new int[][]{{1}, {2, 3}};
                int[][] bare = {{4}, {}};
                return m.length + " " + m[1].length + " " + m[1][2] + " " + partial.length + " " + partial[0]
                        + " " + literal[1][1] + " " + literal.length + " " + bare[0][0] + " " + bare[1].length;"""));
    }

    /** A catch parameter with a qualified type name; a multi-catch catches every alternative. */
    @Test
    void qualifiedCatchType() throws Exception {
        assertEquals("x y", run("""
                String first;
                try {
                    throw new java.io.FileNotFoundException("x");
                } catch (java.io.FileNotFoundException e) {
                    first = e.getMessage();
                }
                try {
                    throw new IllegalStateException("y");
                } catch (IllegalArgumentException | IllegalStateException e) {
                    return first + " " + e.getMessage();
                }"""));
    }
}
