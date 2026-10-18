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
 * Compileert via de NabuCompiler-pipeline (ASM-backend) en voert de klasse uit.
 *
 * <ul>
 *   <li>Enhanced-for over een array evalueert de array-expressie één keer
 *       (JLS §14.14.2); Lower plaatste haar eerder in zowel de conditie als de
 *       element-toegang.</li>
 *   <li>Een constructor-aanroep kiest de meest specifieke overload (JLS
 *       §15.12.2.5): {@code Binding(Object, String)} delegeert met
 *       {@code this(asScope(o), name)} naar {@code Binding(IScope, String)} en
 *       riep zichzelf eerder eindeloos aan.</li>
 * </ul>
 */
class EnhancedForAndCtorOverloadRunTest {

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
    void arrayExpressionIsEvaluatedOnce() throws Exception {
        final var cls = compileAndLoad("ForOnce", """
                package mini;
                public class ForOnce {
                    static int calls;
                    static String[] items() {
                        calls++;
                        String[] values = { "a", "bb", "ccc", "dddd" };
                        return values;
                    }
                    public static int run() {
                        int sum = 0;
                        for (String v : items()) {
                            sum += v.length();
                        }
                        return sum * 100 + calls;
                    }
                }
                """);
        assertEquals(1001, cls.getMethod("run").invoke(null),
                "som 10, items() precies één keer aangeroepen");
    }

    /**
     * Een inner class die een veld van de outer class zonder {@code this.}
     * leest en schrijft, moet dat via de enclosing instance doen (picocli's
     * {@code CaseAwareKeySet.iterator()} las {@code targetMap} uit zichzelf).
     */
    @Test
    void innerClassAccessesOuterFieldThroughEnclosingInstance() throws Exception {
        final var cls = compileAndLoad("OuterField", """
                package mini;
                public class OuterField {
                    String label = "outer";
                    int hits;
                    String outerName() { return label + "!"; }
                    class Inner {
                        private String own = "inner";
                        String read() { hits = hits + 1; return label + "/" + own; }
                        String viaOuterMethod() { return outerName(); }
                    }
                    public static String run() {
                        OuterField o = new OuterField();
                        Inner i = o.new Inner();
                        String r = i.read();
                        i.read();
                        return r + "/" + o.hits + "/" + i.viaOuterMethod();
                    }
                }
                """);
        assertEquals("outer/inner/2/outer!", cls.getMethod("run").invoke(null));
    }

    /**
     * picocli's {@code ArgSpec} roept {@code RuntimeTypeInfo.create(...,
     * Collections.<String>emptyList(), ...)} aan; de overload met een
     * {@code java.lang.reflect.Type} op die plek is niet toepasbaar op een List.
     */
    @Test
    void genericCallArgumentSelectsTheApplicableOverload() throws Exception {
        final var cls = compileAndLoad("GenericArg", """
                package mini;
                import java.lang.reflect.Type;
                import java.util.Collections;
                import java.util.List;
                public class GenericArg {
                    static String pick(Type t) { return "type"; }
                    static String pick(List<String> l) { return "list"; }
                    public static String run() {
                        return pick(Collections.<String>emptyList()) + "/"
                                + pick(Collections.emptyList());
                    }
                }
                """);
        assertEquals("list/list", cls.getMethod("run").invoke(null));
    }

    /**
     * Een array met een runtime-berekende grootte ({@code new String[n + 1]})
     * kreeg lengte 0: alleen een constante grootte werd doorgegeven.
     */
    @Test
    void arrayWithComputedSizeHasThatLength() throws Exception {
        final var cls = compileAndLoad("ArrSize", """
                package mini;
                public class ArrSize {
                    static String[] withFirst(String first, String... rest) {
                        String[] copy = new String[rest.length + 1];
                        copy[0] = first;
                        System.arraycopy(rest, 0, copy, 1, rest.length);
                        return copy;
                    }
                    public static String run() {
                        String[] r = withFirst("a", "b", "c");
                        return r.length + ":" + r[0] + r[1] + r[2];
                    }
                }
                """);
        assertEquals("3:abc", cls.getMethod("run").invoke(null));
    }

    /**
     * {@code Comparator<String>} met alleen {@code compare(String, String)}:
     * {@code Arrays.sort} roept {@code compare(Object, Object)} aan, dus de
     * klasse heeft een bridge-methode nodig (anders AbstractMethodError).
     */
    @Test
    void genericInterfaceImplementationGetsABridgeMethod() throws Exception {
        final var cls = compileAndLoad("Bridge", """
                package mini;
                import java.util.Arrays;
                import java.util.Comparator;
                public class Bridge {
                    static class ShortestFirst implements Comparator<String> {
                        public int compare(String a, String b) { return a.length() - b.length(); }
                    }
                    public static String run() {
                        String[] names = { "ccc", "a", "bb" };
                        Arrays.sort(names, new ShortestFirst());
                        return names[0] + names[1] + names[2];
                    }
                }
                """);
        assertEquals("abbccc", cls.getMethod("run").invoke(null));
    }

    /**
     * Een toewijzing binnen een conditie (picocli's quoteElements:
     * {@code if (e != null && (pos = e.indexOf(" (")) >= 0) suffix = e.substring(pos)})
     * moet de toegewezen waarde ook ná de conditie hebben.
     */
    @Test
    void assignmentInsideConditionKeepsItsValue() throws Exception {
        final var cls = compileAndLoad("AssignCond", """
                package mini;
                public class AssignCond {
                    static String tail(String e) {
                        int pos;
                        if (e != null && (pos = e.indexOf(":")) >= 0) {
                            return e.substring(pos);
                        }
                        return "-";
                    }
                    public static String run() { return tail("ab:cd") + tail("x") + tail(null); }
                }
                """);
        assertEquals(":cd--", cls.getMethod("run").invoke(null));
    }

    /** {@code String +=} concateneert (picocli's quoteElements: {@code result += "'" + x + "'"}). */
    @Test
    void compoundStringConcatenation() throws Exception {
        final var cls = compileAndLoad("StrConcat", """
                package mini;
                public class StrConcat {
                    public static String run() {
                        String result = "";
                        String[] items = { "a", "b" };
                        for (int i = 0; i < items.length; i++) {
                            if (i > 0) result += ", ";
                            result += "'" + items[i] + "'";
                        }
                        return result;
                    }
                }
                """);
        assertEquals("'a', 'b'", cls.getMethod("run").invoke(null));
    }

    @Test
    void constructorDelegationSelectsMostSpecificOverload() throws Exception {
        final var cls = compileAndLoad("CtorPick", """
                package mini;
                public class CtorPick {
                    public interface IScope { Object get(); }
                    public static class ObjectScope implements IScope {
                        private final Object value;
                        public ObjectScope(Object value) { this.value = value; }
                        public Object get() { return value; }
                        static IScope asScope(Object scope) {
                            return scope instanceof IScope ? ((IScope) scope) : new ObjectScope(scope);
                        }
                    }
                    public static class Binding {
                        final IScope scope;
                        final String name;
                        Binding(Object scope, String name) { this(ObjectScope.asScope(scope), name + "o"); }
                        Binding(IScope scope, String name) { this.scope = scope; this.name = name + "s"; }
                    }
                    public static String run() {
                        Binding viaScope = new Binding(new ObjectScope("x"), "a");
                        Binding viaObject = new Binding("y", "b");
                        return viaScope.name + viaObject.name + viaObject.scope.get();
                    }
                }
                """);
        assertEquals("asbosy", cls.getMethod("run").invoke(null));
    }

    /**
     * Een primitieve waarde toewijzen aan een statisch wrapper-veld boxt en
     * slaat op (picocli's {@code jansiInstalled = calcIsJansiConsoleInstalled()}).
     * De store verdween, waarna de unboxing van het veld een NPE gaf.
     */
    @Test
    void primitiveAssignedToStaticWrapperFieldIsBoxedAndStored() throws Exception {
        final var cls = compileAndLoad("Boxed", """
                package mini;
                public class Boxed {
                    static Boolean cached;
                    static boolean calc() { return true; }
                    static boolean get() {
                        if (cached == null) { cached = calc(); }
                        return cached;
                    }
                    static boolean local(Boolean in) {
                        Boolean b = in;
                        if (b == null) { b = false; }
                        return !b;
                    }
                    static int arr() { Integer[] a = new Integer[1]; a[0] = 3; return a[0]; }
                    Character comment = '#';
                    static Boolean flag = true;
                    Integer code;
                    Boxed code(int v) { code = v; return this; }
                    int code() { return code == null ? 7 : code; }
                    public static int run() {
                        return (get() ? 1 : 0) + (local(null) ? 10 : 0) + new Boxed().code(2).code() * 100 + arr() * 1000
                                + ("#".equals(new Boxed().comment.toString()) && flag ? 10000 : 0);
                    }
                }
                """);
        assertEquals(13211, cls.getMethod("run").invoke(null));
    }

    /**
     * picocli's {@code UnmatchedArgumentException.quoteElements}: een string
     * die in een for-each-lus groeit, en een toewijzing in een
     * {@code &&}-conditie die in de then-tak gebruikt wordt.
     */
    @Test
    void loopAccumulatorAndAssignmentInAndCondition() throws Exception {
        final var cls = compileAndLoad("Quote", """
                package mini;
                import java.util.Arrays;
                import java.util.List;
                public class Quote {
                    static String quote(List<String> list) {
                        String result = "", suffix = "";
                        int pos;
                        for (String element : list) {
                            if (result.length() > 0) { result += ", "; }
                            if (element != null && (pos = element.indexOf(" (while")) >= 0) {
                                suffix = element.substring(pos);
                                element = element.substring(0, pos);
                            }
                            result += "'" + element + "'" + suffix;
                            suffix = "";
                        }
                        return result;
                    }
                    public static String run() {
                        return quote(Arrays.asList("--version", "x (while y)"));
                    }
                }
                """);
        assertEquals("'--version', 'x' (while y)", cls.getMethod("run").invoke(null));
    }

    /**
     * Interface-velden zijn impliciet public static final (JLS §9.3).
     * picocli's {@code Style.on()} las {@code IStyle.CSI} als instantieveld
     * van de enum-constante.
     */
    @Test
    void interfaceFieldIsImplicitlyStatic() throws Exception {
        final var cls = compileAndLoad("Styles", """
                package mini;
                import java.util.ArrayList;
                import java.util.List;
                public class Styles {
                    public interface IStyle {
                        String CSI = "<";
                        List<String> SEEN = new ArrayList<>();
                        String on();
                    }
                    public enum Style implements IStyle {
                        red(31), bold(1);
                        private final int code;
                        Style(int code) { this.code = code; }
                        public String on() { SEEN.add(name()); return CSI + code + "m"; }
                    }
                    public static String run() {
                        return Style.red.on() + Style.bold.on() + IStyle.SEEN.size();
                    }
                }
                """);
        assertEquals("<31m<1m2", cls.getMethod("run").invoke(null));
    }

    /**
     * {@code values()} geeft {@code (Color[]) $VALUES.clone()}: de cast naar een
     * array-type verdween in de ASM-backend (internal name van een array is
     * zijn descriptor), en de verifier weigerde de klasse.
     */
    @Test
    void nestedEnumValues() throws Exception {
        final var cls = compileAndLoad("NestedEnum", """
                package mini;
                public class NestedEnum {
                    public enum Color { RED, GREEN }
                    public static int run() {
                        return Color.values().length;
                    }
                }
                """);
        assertEquals(2, cls.getMethod("run").invoke(null));
    }

    @Test
    void nestedEnumWithConstructorValues() throws Exception {
        final var cls = compileAndLoad("NestedEnum2", """
                package mini;
                public class NestedEnum2 {
                    public enum Color {
                        RED(1), GREEN(2);
                        private final int code;
                        Color(int code) { this.code = code; }
                    }
                    public static int run() {
                        return Color.values().length;
                    }
                }
                """);
        assertEquals(2, cls.getMethod("run").invoke(null));
    }

    /**
     * Switch-statements ontbraken in de Java-frontend (ze verdwenen
     * geruisloos), en een switch over een enum gebruikte een nooit gevulde
     * $SwitchMap. picocli's TextTable.putValue gooide "IllegalStateException:
     * WRAP".
     */
    @Test
    void nestedEnumSwitch() throws Exception {
        final var cls = compileAndLoad("Cols", """
                package mini;
                public class Cols {
                    public static class Column {
                        public enum Overflow { TRUNCATE, SPAN, WRAP }
                        final Overflow overflow;
                        Column(Overflow o) { overflow = o; }
                    }
                    static int put(Column column) {
                        switch (column.overflow) {
                            case TRUNCATE:
                                return 1;
                            case SPAN:
                                int x = 2;
                                return x;
                            case WRAP:
                                int y = 3;
                                do { y++; } while (y < 3);
                                return y;
                        }
                        throw new IllegalStateException(column.overflow.toString());
                    }
                    public static int run() {
                        return put(new Column(Column.Overflow.TRUNCATE)) * 100
                            + put(new Column(Column.Overflow.SPAN)) * 10
                            + put(new Column(Column.Overflow.WRAP));
                    }
                }
                """);
        assertEquals(124, cls.getMethod("run").invoke(null));
    }

    @Test
    void intSwitchWithLocalsInCases() throws Exception {
        final var cls = compileAndLoad("IntSw", """
                package mini;
                public class IntSw {
                    static int put(int v) {
                        switch (v) {
                            case 0:
                                return 1;
                            case 1:
                                int x = 2;
                                return x;
                        }
                        throw new IllegalStateException("x" + v);
                    }
                    public static int run() {
                        return put(0) * 10 + put(1);
                    }
                }
                """);
        assertEquals(12, cls.getMethod("run").invoke(null));
    }

    @Test
    void intSwitchReturnAndBreak() throws Exception {
        final var cls = compileAndLoad("IntSw2", """
                package mini;
                public class IntSw2 {
                    static int put(int v) {
                        switch (v) {
                            case 0:
                                return 1;
                            case 1:
                                return 2;
                        }
                        return 9;
                    }
                    static int put2(int v) {
                        int r = 9;
                        switch (v) {
                            case 0:
                                r = 1;
                                break;
                            case 1:
                                r = 2;
                                break;
                        }
                        return r;
                    }
                    public static int run() {
                        return put(0) * 1000 + put(1) * 100 + put2(0) * 10 + put2(1);
                    }
                }
                """);
        assertEquals(1212, cls.getMethod("run").invoke(null));
    }

    /** break/continue (ook gelabeld), synchronized en switch-rules ontbraken in de Java-frontend. */
    @Test
    void breakContinueLabelsSynchronizedAndSwitchRules() throws Exception {
        final var cls = compileAndLoad("Flow", """
                package mini;
                public class Flow {
                    static final Object LOCK = new Object();
                    static int loops() {
                        int sum = 0;
                        for (int i = 0; i < 10; i++) {
                            if (i == 2) { continue; }
                            if (i == 5) { break; }
                            sum += i;
                        }
                        outer:
                        for (int a = 0; a < 3; a++) {
                            for (int b = 0; b < 3; b++) {
                                if (b == 1) { continue outer; }
                                if (a == 2) { break outer; }
                                sum += 100;
                            }
                        }
                        int n = 0;
                        while (true) { n++; if (n == 3) break; }
                        return sum + n * 1000;
                    }
                    static int locked() {
                        int v = 0;
                        synchronized (LOCK) { v = 7; }
                        return v;
                    }
                    static String rules(int v) {
                        String r;
                        switch (v) {
                            case 1, 2 -> r = "small";
                            case 3 -> { r = "three"; }
                            default -> r = "other";
                        }
                        return r;
                    }
                    public static String run() {
                        return loops() + ":" + locked() + ":" + rules(2) + rules(3) + rules(9);
                    }
                }
                """);
        assertEquals("3208:7:smallthreeother", cls.getMethod("run").invoke(null));
    }

    /** String- en char-literals worden ge-unescaped (JLS 3.3, 3.10.7). */
    @Test
    void literalEscapes() throws Exception {
        final var cls = compileAndLoad("Esc", """
                package mini;
                public class Esc {
                    public static String run() {
                        String s = "a\\nb\\tc\\u0041\\\\u0041";
                        char nl = '\\n';
                        char esc = '\\u001B';
                        return s.length() + ":" + (int) nl + ":" + (int) esc + ":" + s.charAt(5) + s.substring(6);
                    }
                }
                """);
        assertEquals("12:10:27:A\\u0041", cls.getMethod("run").invoke(null));
    }

    /** Een postfix-increment als argument of index werkt de variabele bij (picocli's getCJKAdjustedLength hing). */
    @Test
    void postfixIncrementInArgumentAndIndex() throws Exception {
        final var cls = compileAndLoad("Post", """
                package mini;
                public class Post {
                    static int id(int v) { return v; }
                    public static String run() {
                        String s = "abc";
                        int i = 0;
                        int count = 0;
                        while (i < s.length()) {
                            char c = s.charAt(i++);
                            count++;
                        }
                        int[] arr = new int[3];
                        arr[0] = 5; arr[1] = 6; arr[2] = 7;
                        int j = 0;
                        int sum = arr[j++] + arr[j++];
                        int k = 1;
                        int m = id(k++) + id(k--) + id(++k);
                        return count + ":" + i + ":" + sum + ":" + j + ":" + m + ":" + k;
                    }
                }
                """);
        assertEquals("3:3:11:2:5:2", org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> cls.getMethod("run").invoke(null)));
    }
}
