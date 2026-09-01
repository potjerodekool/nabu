package io.github.potjerodekool.nabu.compiler.lang.support.java;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

class JavaLanguageSupportTest extends AbstractCompilerTest {

    @Override
    protected String getClassPath() {
        return "C:\\Users\\evert\\.m2\\repository\\org\\projectlombok\\lombok\\1.18.42\\lombok-1.18.42.jar";
    }

    @Test
    void parse() {
        final var parser = new JavaLanguageSupport();
        final var fileObject = createFileObject(
                """                        
                        public class MyClass {
                            private final Map<String, byte[]> imageStore = new HashMap<String, byte[]>();
                        }"""
        );

        final var cu = parser.parse(
                fileObject,
                getCompilerContext()
        );

        // C:\Users\evert\.m2\repository\org\projectlombok\lombok\1.18.42\lombok-1.18.42.jar!\META-INF\MANIFEST.MF
        System.out.println(cu);
    }

    @Test
    void findLombok() {
        try {
            var resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
            resources.asIterator().forEachRemaining(resource -> {
                try (var input = resource.openStream()) {
                    final var properties = new Properties();
                    properties.load(input);
                    System.out.println("module " + properties.getProperty("Automatic-Module-Name"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testValidClassDeclaration() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("public class Test { }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testValidMethodWithParameters() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method(int x, String y) { } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testValidLoopStatement() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { for(int i=0; i<10; i++) { } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testTryCatchFinally() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method() {
                        try {
                            int x = 1;
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            System.out.println("done");
                        }
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testSwitchStatement() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method(int x) {
                        switch (x) {
                            case 1:
                                break;
                            case 2:
                                return;
                            default:
                                break;
                        }
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testAnnotations() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                @interface MyAnnotation {
                    String value() default "";
                    int count() default 0;
                }
                public class Test {
                    @MyAnnotation(value = "hello", count = 42)
                    public void annotated() {}
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testLambdaExpression() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                import java.util.function.*;
                import java.util.List;
                import java.util.stream.*;
                public class Test {
                    void method() {
                        List<String> list = List.of("a", "b", "c");
                        list.stream().filter(s -> s.length() > 1).map(s -> s.toUpperCase()).collect(Collectors.toList());
                        list.forEach(System.out::println);
                        Function<String, Integer> f = String::length;
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testInnerClasses() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    private int x = 10;
                    class Inner {
                        int y = x;
                    }
                    static class StaticInner {
                        int z = 1;
                    }
                    interface InnerInterface {
                        void doSomething();
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testGenericMethods() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                import java.util.*;
                public class Test {
                    <T extends Comparable<T>> T max(T a, T b) {
                        return a.compareTo(b) >= 0 ? a : b;
                    }
                    <T, R> R apply(T input, Function<T, R> func) {
                        return func.apply(input);
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testEnum() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    enum Color {
                        RED, GREEN, BLUE;
                        private int code;
                        Color() { this.code = ordinal(); }
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testInterfaceWithDefaultMethods() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public interface TestInterface {
                    void abstractMethod();
                    default void defaultMethod() {
                        System.out.println("default");
                    }
                    static void staticMethod() {
                        System.out.println("static");
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testRecord() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                import java.util.List;
                public class Test {
                    record Point(int x, int y) {}
                    record Person(String name, List<String> addresses) {
                        public Person { }
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testSwitchExpression() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    String method(int x) {
                        return switch (x) {
                            case 1 -> "one";
                            case 2 -> "two";
                            default -> "other";
                        };
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testEnhancedFor() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                import java.util.List;
                public class Test {
                    void method() {
                        int[] arr = {1, 2, 3};
                        for (int i : arr) {
                            System.out.println(i);
                        }
                        for (String s : List.of("a", "b")) {
                            System.out.println(s);
                        }
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testThrowAndAssert() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method() throws Exception {
                        throw new RuntimeException("error");
                    }
                    void check() {
                        assert 1 == 1 : "should be true";
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testConditionalExpressions() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method() {
                        int x = 1 > 2 ? 3 : 4;
                        boolean b = true && false || true;
                        int y = 1 + 2 - 3 * 4 / 5;
                        int z = 1 << 2 >> 3;
                        boolean eq = "a".equals("b");
                        int ternary = x > 0 ? (x < 10 ? 1 : 2) : 3;
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testCastAndInstanceof() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method(Object obj) {
                        if (obj instanceof String s) {
                            System.out.println(s.length());
                        }
                        String s = (String) obj;
                        int i = (int) 42L;
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testAssignmentOperators() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method() {
                        int x = 0;
                        x += 1;
                        x -= 2;
                        x *= 3;
                        x /= 4;
                        x %= 5;
                        x &= 6;
                        x |= 7;
                        x ^= 8;
                        x <<= 9;
                        x >>= 10;
                        x >>>= 11;
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testUnaryExpressions() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method() {
                        int x = 5;
                        int a = +x;
                        int b = -x;
                        boolean c = !true;
                        int d = ~x;
                        x++;
                        x--;
                        ++x;
                        --x;
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testWhileAndDo() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method() {
                        while (true) { break; }
                        int i = 0;
                        do { i++; } while (i < 10);
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testArrayAccess() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method() {
                        int[] arr = new int[10];
                        int[][] arr2 = new int[5][5];
                        arr[0] = 1;
                        int x = arr[arr[0]];
                        int len = arr.length;
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testConstructorAndSuper() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    Test() {}
                    Test(int x) { this(); }
                    class Inner extends Test {
                        Inner() { super(42); }
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testTypeParameters() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                import java.util.*;
                public class Test<K, V extends Comparable<V>> {
                    Map<K, V> map = new HashMap<>();
                    <T extends K> void genericMethod(T item) {}
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testVarargsAndMultiCatch() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                import java.io.*;
                public class Test {
                    void method(String... args) {}
                    void multiCatch() {
                        try {
                            int x = Integer.parseInt("1");
                        } catch (NumberFormatException | NullPointerException e) {
                            e.printStackTrace();
                        }
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testMethodReference() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                import java.util.*;
                import java.util.stream.*;
                public class Test {
                    void method() {
                        List<String> list = List.of("hello", "world");
                        list.stream().map(String::toUpperCase).collect(Collectors.toList());
                        list.sort(String::compareToIgnoreCase);
                        Runnable r = System.out::println;
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testPostfixExpressions() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("""
                public class Test {
                    void method() {
                        int x = 0;
                        x++;
                        x--;
                    }
                }""".getBytes());
        JavaCompilerParser.parse(inputStream);
    }
}