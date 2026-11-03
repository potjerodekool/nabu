package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The constructs that the lowering handles (for-each, enum and string switches, try/finally, try-with-resources,
 * compound assignments and increments of boxed values, conditional promotion, string concatenation, varargs,
 * inner classes), in simple places and in places that are deep in a tree.
 */
class LowerBreadthRunTest {

    @TempDir
    Path tempDir;

    private Object run(final String className,
                       final String body) throws Exception {
        final var code = "package mini;\npublic class " + className + " {\n" + body + "\n}\n";
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
                .build();

        final var compiler = new NabuCompiler();
        final var messages = new ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                messages.add(d.getMessage(Locale.ROOT).toString());
            }
        });
        final var rc = compiler.compile(options);
        assertEquals(0, messages.size(), String.join("\n", messages));
        assertEquals(0, rc, String.join("\n", messages));

        final var loader = new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()},
                getClass().getClassLoader());
        return loader.loadClass("mini." + className).getMethod("run").invoke(null);
    }

    // ---- for-each

    @Test
    void forEachOverAnIterableWithUnboxingAndANestedLoop() throws Exception {
        assertEquals("36 6", run("Each1", """
                    public static String run() {
                        java.util.List<Integer> numbers = java.util.List.of(1, 2, 3);
                        int sum = 0;
                        for (int a : numbers) {
                            for (int b : numbers) {
                                sum += a * b;
                            }
                        }
                        long total = 0;
                        for (long x : new int[]{1, 2, 3}) {
                            total += x;
                        }
                        return sum + " " + total;
                    }
                """));
    }

    @Test
    void forEachWithLabelsBreakAndContinue() throws Exception {
        assertEquals("1-1 2-1 ", run("Each2", """
                    public static String run() {
                        StringBuilder sb = new StringBuilder();
                        outer:
                        for (int a : new int[]{1, 2, 3}) {
                            for (int b : new int[]{1, 2}) {
                                if (a == 3) {
                                    break outer;
                                }
                                if (b == 2) {
                                    continue outer;
                                }
                                sb.append(a).append('-').append(b).append(' ');
                            }
                        }
                        return sb.toString();
                    }
                """));
    }

    @Test
    void forEachOverAMapEntrySetAndAMethodResult() throws Exception {
        assertEquals("a=1 b=2 | x y", run("Each3", """
                    static java.util.List<String> names() {
                        return java.util.List.of("x", "y");
                    }
                    public static String run() {
                        java.util.Map<String, Integer> map = new java.util.TreeMap<>();
                        map.put("a", 1);
                        map.put("b", 2);
                        StringBuilder sb = new StringBuilder();
                        for (java.util.Map.Entry<String, Integer> e : map.entrySet()) {
                            sb.append(e.getKey()).append('=').append(e.getValue()).append(' ');
                        }
                        sb.append("|");
                        for (String n : names()) {
                            sb.append(' ').append(n);
                        }
                        return sb.toString();
                    }
                """));
    }

    // ---- enum switches

    @Test
    void enumSwitchWithFallthroughAndDefault() throws Exception {
        assertEquals("a-b-c|c|d", run("EnumSw1", """
                    enum Level { LOW, MID, HIGH, MAX }
                    static String old(Level level) {
                        StringBuilder sb = new StringBuilder();
                        switch (level) {
                            case LOW:
                                sb.append("a-");
                            case MID:
                                sb.append("b-");
                            case HIGH:
                                sb.append("c");
                                break;
                            default:
                                sb.append("d");
                        }
                        return sb.toString();
                    }
                    public static String run() {
                        return old(Level.LOW) + "|" + old(Level.HIGH) + "|" + old(Level.MAX);
                    }
                """));
    }

    @Test
    void enumSwitchExpressionWithYieldInsideALambda() throws Exception {
        assertEquals("1 5", run("EnumSw2", """
                    enum Level { LOW, HIGH }
                    public static String run() {
                        java.util.function.Function<Level, Integer> f = level -> switch (level) {
                            case LOW -> 1;
                            case HIGH -> {
                                int base = 2;
                                yield base + 3;
                            }
                        };
                        return f.apply(Level.LOW) + " " + f.apply(Level.HIGH);
                    }
                """));
    }

    @Test
    void enumWithBodiesFieldsAndValues() throws Exception {
        assertEquals("[PLUS, TIMES] 7 12 PLUS", run("EnumBody1", """
                    enum Op {
                        PLUS("+") {
                            int apply(int a, int b) { return a + b; }
                        },
                        TIMES("*") {
                            int apply(int a, int b) { return a * b; }
                        };
                        final String symbol;
                        Op(String symbol) { this.symbol = symbol; }
                        abstract int apply(int a, int b);
                    }
                    public static String run() {
                        return java.util.Arrays.toString(Op.values()) + " " + Op.PLUS.apply(3, 4) + " "
                                + Op.TIMES.apply(3, 4) + " " + Op.valueOf("PLUS");
                    }
                """));
    }

    // ---- string switches

    @Test
    void stringSwitchWithHashCollisionsAndFallthrough() throws Exception {
        assertEquals("aa bb x one-two", run("StrSw1", """
                    static String pick(String s) {
                        switch (s) {
                            case "Aa": return "aa";
                            case "BB": return "bb";
                            default: return "x";
                        }
                    }
                    static String chain(String s) {
                        StringBuilder sb = new StringBuilder();
                        switch (s) {
                            case "one":
                                sb.append("one-");
                            case "two":
                                sb.append("two");
                                break;
                            default:
                                sb.append("other");
                        }
                        return sb.toString();
                    }
                    public static String run() {
                        return pick("Aa") + " " + pick("BB") + " " + pick("C") + " " + chain("one");
                    }
                """));
    }

    @Test
    void stringSwitchExpressionWithYield() throws Exception {
        assertEquals("1 22 0", run("StrSw2", """
                    static String value(String s) {
                        return String.valueOf(switch (s) {
                            case "a" -> 1;
                            case "b", "c" -> {
                                int x = 2;
                                yield x * 11;
                            }
                            default -> 0;
                        });
                    }
                    public static String run() {
                        return value("a") + " " + value("c") + " " + value("z");
                    }
                """));
    }

    // ---- try / finally / resources

    @Test
    void finallyOverridesAReturnAndRunsOnBreak() throws Exception {
        assertEquals("2 body-fin", run("Fin1", """
                    @SuppressWarnings("finally")
                    static int override() {
                        try {
                            return 1;
                        } finally {
                            return 2;
                        }
                    }
                    public static String run() {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 3; i++) {
                            try {
                                sb.append("body");
                                break;
                            } finally {
                                sb.append("-fin");
                            }
                        }
                        return override() + " " + sb;
                    }
                """));
    }

    @Test
    void nestedTryCatchFinallyOrder() throws Exception {
        assertEquals("t1 c1 f1 | t2 f2 c2 f3", run("Fin2", """
                    public static String run() {
                        StringBuilder sb = new StringBuilder();
                        try {
                            sb.append("t1 ");
                            throw new IllegalStateException("x");
                        } catch (IllegalStateException e) {
                            sb.append("c1 ");
                        } finally {
                            sb.append("f1 ");
                        }
                        sb.append("| ");
                        try {
                            try {
                                sb.append("t2 ");
                                throw new IllegalArgumentException("y");
                            } finally {
                                sb.append("f2 ");
                            }
                        } catch (IllegalArgumentException | IllegalStateException e) {
                            sb.append("c2 ");
                        } finally {
                            sb.append("f3");
                        }
                        return sb.toString();
                    }
                """));
    }

    @Test
    void tryWithResourcesClosesInReverseOrderAndKeepsSuppressed() throws Exception {
        assertEquals("open-a open-b body close-b close-a | boom suppressed=1", run("Res1", """
                    static StringBuilder log = new StringBuilder();
                    static class Res implements AutoCloseable {
                        final String name;
                        final boolean failOnClose;
                        Res(String name, boolean failOnClose) {
                            this.name = name;
                            this.failOnClose = failOnClose;
                            log.append("open-").append(name).append(' ');
                        }
                        public void close() {
                            log.append("close-").append(name).append(' ');
                            if (failOnClose) {
                                throw new IllegalStateException("close");
                            }
                        }
                    }
                    public static String run() {
                        try (Res a = new Res("a", false); Res b = new Res("b", false)) {
                            log.append("body ");
                        }
                        String first = log.toString().trim();
                        String second;
                        try (Res c = new Res("c", true)) {
                            throw new RuntimeException("boom");
                        } catch (RuntimeException e) {
                            second = e.getMessage() + " suppressed=" + e.getSuppressed().length;
                        }
                        return first + " | " + second;
                    }
                """));
    }

    @Test
    void tryWithResourcesInALambdaThatIsAnArgumentAndInACaseBody() throws Exception {
        assertEquals("ok close close ok2 close", run("Res2", """
                    static StringBuilder log = new StringBuilder();
                    static class Res implements AutoCloseable {
                        public void close() {
                            log.append(" close");
                        }
                    }
                    static String apply(java.util.function.Supplier<String> s) {
                        return s.get();
                    }
                    public static String run() {
                        String a = apply(() -> {
                            try (Res r = new Res()) {
                                return "ok";
                            }
                        });
                        int mode = 1;
                        String b = "";
                        switch (mode) {
                            case 1 -> {
                                try (Res r = new Res()) {
                                    b = " ok2";
                                }
                            }
                            default -> b = "none";
                        }
                        return a + log + b + log.substring(log.length() - 6);
                    }
                """));
    }

    // ---- compound assignment, increments, promotion

    @Test
    void incrementOfABoxedLocal() throws Exception {
        assertEquals("5 6", run("Box1", """
                    public static String run() {
                        Integer i = 5;
                        Integer j = i++;
                        return j + " " + i;
                    }
                """));
    }

    @Test
    void compoundAssignmentOfABoxedLocal() throws Exception {
        assertEquals("7", run("Box2", """
                    public static String run() {
                        Integer i = 5;
                        i += 2;
                        return String.valueOf(i);
                    }
                """));
    }

    @Test
    void incrementAndCompoundAssignmentOfABoxedField() throws Exception {
        assertEquals("7", run("Box3", """
                    static Integer field = 5;
                    public static String run() {
                        field++;
                        field += 1;
                        return String.valueOf(field);
                    }
                """));
    }

    @Test
    void compoundAssignmentOnArrayElements() throws Exception {
        assertEquals("3 7", run("ArrOp1", """
                    public static String run() {
                        int[] a = {1, 5};
                        int i = 0;
                        a[i++] += 2;
                        a[1] += 2;
                        return a[0] + " " + a[1];
                    }
                """));
    }

    @Test
    void compoundAssignmentOnPlainArrayElements() throws Exception {
        assertEquals("3 7 4", run("ArrOp2", """
                    public static String run() {
                        int[] a = {1, 5, 2};
                        a[0] += 2;
                        a[1] = a[1] + 2;
                        a[2] *= 2;
                        return a[0] + " " + a[1] + " " + a[2];
                    }
                """));
    }

    @Test
    void compoundAssignmentOnNarrowTypesWrapsAround() throws Exception {
        assertEquals("x 4 -126 66", run("Narrow1", """
                    public static String run() {
                        char c = 'w';
                        c += 1;
                        short s = 2;
                        s *= 2;
                        byte b = 127;
                        b += 3;
                        byte d = 64;
                        d += 2;
                        return c + " " + s + " " + b + " " + d;
                    }
                """));
    }

    @Test
    void conditionalPromotionAsAnArgumentAndInOperands() throws Exception {
        assertEquals("1.0 a 3 97", run("Promo1", """
                    static String text(Object o) {
                        return String.valueOf(o);
                    }
                    public static String run() {
                        boolean b = true;
                        Integer boxed = 3;
                        char c = 'a';
                        return text(b ? 1 : 2.0) + " " + text(b ? c : 'z') + " " + text(b ? boxed : 0) + " "
                                + (b ? c : 0) * 1;
                    }
                """));
    }

    @Test
    void stringConcatenationOfManyTypes() throws Exception {
        assertEquals("a1b2.5c3dtruenull[1, 2]", run("Concat1", """
                    public static String run() {
                        String s = "a";
                        s += 1;
                        s += "b" + 2.5 + 'c' + 3L + "d" + true + null;
                        return s + java.util.Arrays.toString(new int[]{1, 2});
                    }
                """));
    }

    @Test
    void varargsWithBoxingAndArrays() throws Exception {
        assertEquals("3 0 2 6", run("Var1", """
                    static int count(Object... items) {
                        return items.length;
                    }
                    static int sum(int... values) {
                        int total = 0;
                        for (int v : values) {
                            total += v;
                        }
                        return total;
                    }
                    public static String run() {
                        return count(1, "two", 3.0) + " " + count() + " " + count(new Object[]{1, 2}) + " "
                                + sum(1, 2, 3);
                    }
                """));
    }

    // ---- inner classes

    @Test
    void innerClassesCaptureAndReachTheOuterInstance() throws Exception {
        assertEquals("outer-1 captured-9 local-4", run("Inner1", """
                    int value = 1;
                    class Inner {
                        String show() {
                            return "outer-" + value;
                        }
                    }
                    public static String run() {
                        Inner1 outer = new Inner1();
                        Inner1.Inner inner = outer.new Inner();
                        int captured = 9;
                        java.util.function.Supplier<String> anon = new java.util.function.Supplier<>() {
                            public String get() {
                                return "captured-" + captured;
                            }
                        };
                        class Local {
                            int twice(int x) {
                                return x * 2;
                            }
                        }
                        return inner.show() + " " + anon.get() + " local-" + new Local().twice(2);
                    }
                """));
    }

    // ---- misc

    @Test
    void labeledLoopsDoWhileAndSwitchFallthrough() throws Exception {
        assertEquals("0:0 0:1 1:0 | 3 | 2", run("Loops1", """
                    public static String run() {
                        StringBuilder sb = new StringBuilder();
                        outer:
                        for (int i = 0; i < 3; i++) {
                            for (int j = 0; j < 3; j++) {
                                if (i == 1 && j == 1) {
                                    break outer;
                                }
                                if (j == 2) {
                                    continue outer;
                                }
                                sb.append(i).append(':').append(j).append(' ');
                            }
                        }
                        int n = 0;
                        do {
                            n++;
                        } while (n < 3);
                        int k = 0;
                        switch (n) {
                            case 3:
                                k++;
                            case 4:
                                k++;
                                break;
                            default:
                                k = 100;
                        }
                        return sb.toString().trim() + " | " + n + " | " + k;
                    }
                """));
    }

    @Test
    void initializerOrderOfFieldsAndBlocks() throws Exception {
        assertEquals("s1 s2 i1 i2 ctor", run("Init1", """
                    static StringBuilder log = new StringBuilder();
                    static { log.append("s1 "); }
                    static int s = log.append("s2 ").length();
                    int a = log.append("i1 ").length();
                    { log.append("i2 "); }
                    Init1() { log.append("ctor"); }
                    public static String run() {
                        new Init1();
                        return log.toString();
                    }
                """));
    }

    @Test
    void recordsWithCompactConstructorsAndGenerics() throws Exception {
        assertEquals("Pair[a=1, b=x] true 3 false", run("Rec1", """
                    record Pair<A, B>(A a, B b) {}
                    record Range(int lo, int hi) {
                        Range {
                            if (lo > hi) {
                                int t = lo;
                                lo = hi;
                                hi = t;
                            }
                        }
                        int size() {
                            return hi - lo;
                        }
                    }
                    public static String run() {
                        Pair<Integer, String> p = new Pair<>(1, "x");
                        return p + " " + p.equals(new Pair<>(1, "x")) + " " + new Range(5, 2).size() + " "
                                + new Range(1, 2).equals(new Range(1, 3));
                    }
                """));
    }

    @Test
    void aCompactConstructorValidates() throws Exception {
        assertEquals("bad", run("Rec2", """
                    record Positive(int value) {
                        Positive {
                            if (value < 0) {
                                throw new IllegalArgumentException("bad");
                            }
                        }
                    }
                    public static String run() {
                        try {
                            new Positive(-1);
                            return "no exception";
                        } catch (IllegalArgumentException e) {
                            return e.getMessage();
                        }
                    }
                """));
    }

    @Test
    void aCompactConstructorChangesItsParameters() throws Exception {
        assertEquals("abc 5", run("Rec3", """
                    record Named(String name, int size) {
                        Named {
                            name = name.trim();
                            size = Math.max(size, 5);
                        }
                    }
                    public static String run() {
                        Named n = new Named("  abc ", 1);
                        return n.name() + " " + n.size();
                    }
                """));
    }

    @Test
    void compoundAssignmentConvertsTheResultNotTheOperand() throws Exception {
        assertEquals("5 12 7.5 3", run("Compound3", """
                    public static String run() {
                        int x = 10;
                        x *= 0.5;
                        long l = 10;
                        l += 2;
                        double d = 5;
                        d += 2.5;
                        int y = 7;
                        y /= 2;
                        return x + " " + l + " " + d + " " + y;
                    }
                """));
    }

    @Test
    void staticInitializersRunInTheOrderOfTheSource() throws Exception {
        assertEquals("first a second b", run("StaticOrder1", """
                    static StringBuilder log = new StringBuilder();
                    static { log.append("first "); }
                    static String a = log.append("a ").toString();
                    static { log.append("second "); }
                    static String b = log.append("b").toString();
                    public static String run() {
                        return log.toString();
                    }
                """));
    }

    @Test
    void enumConstantsAreInitializedBeforeTheStaticFields() throws Exception {
        assertEquals("2 A,B", run("EnumOrder1", """
                    enum Letter {
                        A, B;
                        static final java.util.List<String> NAMES = new java.util.ArrayList<>();
                        static {
                            for (Letter l : values()) {
                                NAMES.add(l.name());
                            }
                        }
                    }
                    public static String run() {
                        return Letter.NAMES.size() + " " + String.join(",", Letter.NAMES);
                    }
                """));
    }

    @Test
    void instanceInitializersRunOnceForAConstructorThatDelegates() throws Exception {
        assertEquals("f i ctor1 ctor2", run("InstOrder1", """
                    static StringBuilder log = new StringBuilder();
                    String f = log.append("f ").toString();
                    { log.append("i "); }
                    InstOrder1() {
                        this(1);
                        log.append("ctor2");
                    }
                    InstOrder1(int x) {
                        log.append("ctor1 ");
                    }
                    public static String run() {
                        new InstOrder1();
                        return log.toString();
                    }
                """));
    }

    @Test
    void instanceInitializerCanUseAndChangeFields() throws Exception {
        assertEquals("3 6", run("InstOrder2", """
                    int a = 1;
                    int b;
                    {
                        a = a + 2;
                        b = a * 2;
                    }
                    public static String run() {
                        InstOrder2 o = new InstOrder2();
                        return o.a + " " + o.b;
                    }
                """));
    }
}
