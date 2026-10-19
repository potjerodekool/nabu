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

    /**
     * A bound method reference (JLS 15.13.3) evaluates its receiver once,
     * when it is created, and throws NullPointerException then for a null
     * receiver; it used to be evaluated at every call.
     */
    @Test
    void boundMethodReferenceEvaluatesReceiverOnce() throws Exception {
        assertEquals("1 V1 V1 x12 npe at creation", run("""
                static int calls = 0;
                static String next() {
                    calls++;
                    return "v" + calls;
                }""", """
                java.util.function.Supplier<String> s = next()::toUpperCase;
                String a = s.get();
                String b = s.get();
                StringBuilder sb = new StringBuilder("x");
                java.util.function.Function<String, StringBuilder> append = sb::append;
                sb = new StringBuilder("y");
                append.apply("1");
                String nullCase;
                try {
                    String n = null;
                    java.util.function.Supplier<Integer> length = n::length;
                    nullCase = "no npe";
                } catch (NullPointerException e) {
                    nullCase = "npe at creation";
                }
                return calls + " " + a + " " + b + " " + append.apply("2") + " " + nullCase;"""));
    }

    /**
     * A variable assigned in a catch block: SSA renaming does not reach
     * exception handlers, so the assignment was dropped and the merge after
     * the try read null.
     */
    @Test
    void assignmentInCatchBlock() throws Exception {
        assertEquals("npe ok", run("""
                String r;
                try {
                    String n = null;
                    n.length();
                    r = "no npe";
                } catch (NullPointerException e) {
                    r = "npe";
                }
                String q;
                try {
                    q = "ok";
                } catch (RuntimeException e) {
                    q = "catch";
                }
                return r + " " + q;"""));
    }

    /**
     * A variable assigned in a try body keeps that value on the exception
     * path: SSA promotion turned it into a value that the handler (outside
     * the renaming dominator tree) never saw, so it read the initial value.
     */
    @Test
    void assignmentInTryBodySurvivesException() throws Exception {
        assertEquals("init-c k1 init", run("static int calls = 0;", """
                String r = "init";
                String keep = "k";
                try {
                    keep = keep + "1";
                    Integer.parseInt("x");
                } catch (NumberFormatException e) {
                    r = r + "-c";
                }
                String u = "init";
                try {
                    if (calls < 0) throw new IllegalStateException();
                } catch (IllegalStateException e) {
                    u = "c";
                }
                return r + " " + keep + " " + u;"""));
    }

    /** Sibling scopes with locals of the same name (and different types). */
    @Test
    void siblingScopesWithSameNamedLocals() throws Exception {
        assertEquals("30", run("""
                int total = 0;
                for (int i = 0; i < 3; i++) { total += i; }
                for (int i = 10; i < 12; i++) { total += i; }
                { String s = "a"; total += s.length(); }
                { int s = 5; total += s; }
                return "" + total;"""));
    }

    /**
     * A return in a block lambda targets the lambda's SAM, not an enclosing
     * method (which the lambda body has not): returning a lambda or a method
     * reference from one crashed the resolver.
     */
    @Test
    void blockLambdaReturningLambda() throws Exception {
        assertEquals("7 3", run("""
                java.util.function.Supplier<java.util.function.IntSupplier> f = () -> { return () -> 7; };
                java.util.function.Supplier<java.util.function.ToIntFunction<String>> g = () -> { return String::length; };
                return f.get().getAsInt() + " " + g.get().applyAsInt("abc");"""));
    }

    /**
     * A package-qualified superclass ({@code extends java.util.Random}): the
     * qualifier was looked up as a type, the superclass became an error type
     * and the implicit {@code super()} did not resolve.
     */
    @Test
    void packageQualifiedSuperclass() throws Exception {
        assertEquals("b 7", run("""
                static class Bundle extends java.util.ResourceBundle {
                    protected Object handleGetObject(String key) { return "b"; }
                    public java.util.Enumeration<String> getKeys() { return java.util.Collections.emptyEnumeration(); }
                }
                static class Fixed extends java.util.Random {
                    public int nextInt(int bound) { return 7; }
                }""", """
                return new Bundle().getString("a") + " " + new Fixed().nextInt(10);"""));
    }

    /**
     * A lambda whose parameter has an array type (InvocationHandler's
     * {@code Object[] args}): resolving it threw on the element-less array
     * type, and lowering it had no type tree for an array.
     */
    @Test
    void lambdaWithArrayParameter() throws Exception {
        assertEquals("size=2 hi", run("""
                java.lang.reflect.InvocationHandler h = (proxy, method, args) ->
                        method.getName().equals("size") ? (Object) 2 : "hi";
                java.util.function.Function<String[], Integer> length = a -> a.length;
                java.util.Collection<?> c = (java.util.Collection<?>) java.lang.reflect.Proxy.newProxyInstance(
                        Probe.class.getClassLoader(), new Class<?>[] { java.util.Collection.class }, h);
                return "size=" + (c.size() * length.apply(new String[1])) + " " + c;"""));
    }

    /** A boxing cast to a supertype of the wrapper: {@code valueOf} had the cast type as return type. */
    @Test
    void boxingCastToObject() throws Exception {
        assertEquals("2 true 3.5", run("""
                Object o = (Object) 2;
                java.io.Serializable s = (java.io.Serializable) true;
                Number n = (Number) 3.5;
                return o + " " + s + " " + n;"""));
    }

    /**
     * The Signature attribute of a generic method: the method's type
     * parameters were missing from its symbol (TypeNotPresentException for T)
     * and a bounded wildcard became {@code ?}.
     */
    @Test
    void genericMethodSignature() throws Exception {
        assertEquals("public static <T extends java.lang.Comparable<T>> java.util.List<T> "
                + "mini.Probe.sorted(java.util.Set<? extends T>) 1", run("""
                public static <T extends Comparable<T>> java.util.List<T> sorted(java.util.Set<? extends T> in) { return null; }""", """
                java.lang.reflect.Method m = Probe.class.getMethod("sorted", java.util.Set.class);
                return m.toGenericString() + " " + m.getTypeParameters().length;"""));
    }
}
