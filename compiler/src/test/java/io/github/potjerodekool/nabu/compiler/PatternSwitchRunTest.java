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
import java.util.ArrayList;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test matrix for pattern matching in switch (Java 21 and later): type patterns, guards, record patterns, null
 * labels, unnamed patterns, sealed hierarchies, switch statements and expressions.
 */
// The classes are compiled for release 25 and loaded in this JVM.
@EnabledForJreRange(min = JRE.JAVA_25)
class PatternSwitchRunTest {

    @TempDir
    Path tempDir;

    private Object run(final String className,
                       final String code) throws Exception {
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

    @Test
    void typePatternsOverSealedInterface() throws Exception {
        assertEquals("circle 2.0 square 3.0 rect 6.0", run("Sealed1", """
                package mini;
                public class Sealed1 {
                    sealed interface Shape permits Circle, Square, Rect {}
                    record Circle(double r) implements Shape {}
                    record Square(double s) implements Shape {}
                    record Rect(double w, double h) implements Shape {}
                    static String describe(Shape shape) {
                        return switch (shape) {
                            case Circle c -> "circle " + c.r();
                            case Square s -> "square " + s.s();
                            case Rect r -> "rect " + (r.w() * r.h());
                        };
                    }
                    public static String run() {
                        return describe(new Circle(2)) + " " + describe(new Square(3)) + " " + describe(new Rect(2, 3));
                    }
                }
                """));
    }

    @Test
    void guards() throws Exception {
        assertEquals("small big neg other", run("Guards", """
                package mini;
                public class Guards {
                    static String size(Object o) {
                        return switch (o) {
                            case Integer i when i < 0 -> "neg";
                            case Integer i when i < 10 -> "small";
                            case Integer i -> "big";
                            default -> "other";
                        };
                    }
                    public static String run() {
                        return size(5) + " " + size(50) + " " + size(-1) + " " + size("x");
                    }
                }
                """));
    }

    @Test
    void recordPatternsAndNesting() throws Exception {
        assertEquals("3 10 -1 line 4", run("Records1", """
                package mini;
                public class Records1 {
                    record Point(int x, int y) {}
                    record Line(Point from, Point to) {}
                    static int eval(Object o) {
                        return switch (o) {
                            case Point(int x, int y) -> x + y;
                            case Line(Point(int a, int b), Point(int c, int d)) -> (c - a) + (d - b);
                            default -> -1;
                        };
                    }
                    static String line(Object o) {
                        switch (o) {
                            case Line(Point(var a, var b), Point p) when a < p.x():
                                return "line " + (p.x() - a);
                            default:
                                return "none";
                        }
                    }
                    public static String run() {
                        return eval(new Point(1, 2)) + " " + eval(new Line(new Point(1, 1), new Point(6, 6))) + " "
                                + eval("s") + " " + line(new Line(new Point(1, 0), new Point(5, 0)));
                    }
                }
                """));
    }

    @Test
    void nullLabels() throws Exception {
        assertEquals("null str other null-or-default", run("Nulls", """
                package mini;
                public class Nulls {
                    static String a(Object o) {
                        return switch (o) {
                            case null -> "null";
                            case String s -> "str";
                            default -> "other";
                        };
                    }
                    static String b(Object o) {
                        return switch (o) {
                            case String s -> "str";
                            case null, default -> "null-or-default";
                        };
                    }
                    public static String run() {
                        return a(null) + " " + a("s") + " " + a(1) + " " + b(null);
                    }
                }
                """));
    }

    @Test
    void unnamedPatterns() throws Exception {
        assertEquals("2 5 unknown", run("Unnamed", """
                package mini;
                public class Unnamed {
                    record Point(int x, int y) {}
                    record Box(Object content) {}
                    static String f(Object o) {
                        return switch (o) {
                            case Point(_, int y) -> "" + y;
                            case Box(Point(int x, _)) -> "" + x;
                            case Box(_) -> "unknown";
                            default -> "unknown";
                        };
                    }
                    public static String run() {
                        return f(new Point(1, 2)) + " " + f(new Box(new Point(5, 6))) + " " + f(new Box("x"));
                    }
                }
                """));
    }

    @Test
    void oldStyleStatementWithPatternsAndFallthroughFree() throws Exception {
        assertEquals("i7 s2 o", run("Colon", """
                package mini;
                public class Colon {
                    static String f(Object o) {
                        String r;
                        switch (o) {
                            case Integer i:
                                r = "i" + i;
                                break;
                            case String s:
                                r = "s" + s.length();
                                break;
                            default:
                                r = "o";
                        }
                        return r;
                    }
                    public static String run() {
                        return f(7) + " " + f("ab") + " " + f(1.5);
                    }
                }
                """));
    }

    @Test
    void yieldInBlock() throws Exception {
        assertEquals("sum 5 other", run("Yield1", """
                package mini;
                public class Yield1 {
                    record Pair(int a, int b) {}
                    static String f(Object o) {
                        return switch (o) {
                            case Pair(int a, int b) -> {
                                int s = a + b;
                                yield "sum " + s;
                            }
                            default -> "other";
                        };
                    }
                    public static String run() {
                        return f(new Pair(2, 3)) + " " + f("x");
                    }
                }
                """));
    }

    @Test
    void genericRecordsAndSealedExhaustiveness() throws Exception {
        assertEquals("some 4 none 3", run("Generic1", """
                package mini;
                public class Generic1 {
                    sealed interface Opt<T> permits Some, None {}
                    record Some<T>(T value) implements Opt<T> {}
                    record None<T>() implements Opt<T> {}
                    static String show(Opt<?> o) {
                        return switch (o) {
                            case Some<?>(var v) -> "some " + v;
                            case None<?> n -> "none";
                        };
                    }
                    static int len(Opt<String> o) {
                        return switch (o) {
                            case Some<String>(String s) -> s.length();
                            case None<String>() -> -1;
                        };
                    }
                    public static String run() {
                        return show(new Some<>(4)) + " " + show(new None<>()) + " " + len(new Some<>("abc")) ;
                    }
                }
                """));
    }

    @Test
    void stringAndEnumSelectorsWithPatterns() throws Exception {
        assertEquals("red other-color text:hi", run("Mixed", """
                package mini;
                public class Mixed {
                    enum Color { RED, GREEN }
                    static String f(Object o) {
                        return switch (o) {
                            case Color c when c == Color.RED -> "red";
                            case Color c -> "other-color";
                            case String s -> "text:" + s;
                            default -> "?";
                        };
                    }
                    public static String run() {
                        return f(Color.RED) + " " + f(Color.GREEN) + " " + f("hi");
                    }
                }
                """));
    }

    @Test
    void sealedClassHierarchyWithoutDefault() throws Exception {
        assertEquals("A B C", run("Hier", """
                package mini;
                public class Hier {
                    sealed static abstract class Base permits A, B, C {}
                    static final class A extends Base {}
                    static final class B extends Base {}
                    static non-sealed class C extends Base {}
                    static String f(Base b) {
                        return switch (b) {
                            case A a -> "A";
                            case B bb -> "B";
                            case C c -> "C";
                        };
                    }
                    public static String run() {
                        return f(new A()) + " " + f(new B()) + " " + f(new C());
                    }
                }
                """));
    }

    @Test
    void tmpProbe1() throws Exception {
        assertEquals("2", run("P1", """
                package mini;
                public class P1 {
                    record Point(int x, int y) {}
                    static String f(Object o) {
                        return switch (o) {
                            case Point(_, int y) -> "" + y;
                            default -> "d";
                        };
                    }
                    public static String run() { return f(new Point(1, 2)); }
                }
                """));
    }

    @Test
    void nestedPatternsTestTheTypeOfTheComponent() throws Exception {
        assertEquals("5 none none true false false", run("Nested", """
                package mini;
                public class Nested {
                    record Point(int x, int y) {}
                    record Box(Object content) {}
                    static String f(Object o) {
                        return switch (o) {
                            case Box(Point(int x, _)) -> "" + x;
                            default -> "none";
                        };
                    }
                    static boolean g(Object o) {
                        return o instanceof Box(Point(var x, var y));
                    }
                    public static String run() {
                        return f(new Box(new Point(5, 6))) + " " + f(new Box("x")) + " " + f(new Box(null)) + " "
                                + g(new Box(new Point(1, 2))) + " " + g(new Box("s")) + " " + g(new Box(null));
                    }
                }
                """));
    }

    @Test
    void aTotalNestedPatternMatchesNull() throws Exception {
        assertEquals("null? true", run("Total", """
                package mini;
                public class Total {
                    record Box(Object content) {}
                    static String f(Object o) {
                        return switch (o) {
                            case Box(Object c) -> c == null ? "null?" : "value";
                            default -> "none";
                        };
                    }
                    public static String run() {
                        return f(new Box(null)) + " " + (f(new Box("x")).equals("value"));
                    }
                }
                """));
    }

    @Test
    void switchExpressionInOtherPositions() throws Exception {
        assertEquals("a1 b2 c3 pos", run("Positions", """
                package mini;
                public class Positions {
                    sealed interface T permits A, B {}
                    record A(int n) implements T {}
                    record B(int n) implements T {}
                    static String run1(T t) {
                        String r = switch (t) {
                            case A a -> "a" + a.n();
                            case B b -> "b" + b.n();
                        };
                        return r;
                    }
                    static String run2(T t) {
                        String r;
                        r = switch (t) {
                            case A(int n) -> "a" + n;
                            case B(int n) -> "b" + n;
                        };
                        return r;
                    }
                    static boolean positive(Object o) {
                        if (switch (o) {
                            case Integer i when i > 0 -> true;
                            default -> false;
                        }) {
                            return true;
                        }
                        return false;
                    }
                    public static String run() {
                        return run1(new A(1)) + " " + run1(new B(2)) + " c" + new B(3).n() + " "
                                + (positive(5) && !positive(-5) ? "pos" : "bad");
                    }
                }
                """));
    }

    @Test
    void switchStatementWithRecordPatternsAndArrows() throws Exception {
        assertEquals("p3 l4 n", run("Arrows", """
                package mini;
                public class Arrows {
                    record Point(int x, int y) {}
                    record Line(Point a, Point b) {}
                    static String f(Object o) {
                        String r = "n";
                        switch (o) {
                            case Point(int x, int y) -> r = "p" + (x + y);
                            case Line(Point(int a, int b), Point(int c, int d)) -> {
                                r = "l" + ((c - a) + (d - b));
                            }
                            default -> { }
                        }
                        return r;
                    }
                    public static String run() {
                        return f(new Point(1, 2)) + " " + f(new Line(new Point(0, 0), new Point(2, 2))) + " " + f("x");
                    }
                }
                """));
    }

    @Test
    void constantLabelsNextToNull() throws Exception {
        assertEquals("null A BC BC other r g n one big other", run("Constants", """
                package mini;
                public class Constants {
                    enum Color { RED, GREEN }
                    static String s(String x) {
                        return switch (x) {
                            case null -> "null";
                            case "a" -> "A";
                            case "b", "c" -> "BC";
                            default -> "other";
                        };
                    }
                    static String e(Color c) {
                        return switch (c) {
                            case null -> "n";
                            case RED -> "r";
                            case GREEN -> "g";
                        };
                    }
                    static String i(Integer x) {
                        return switch (x) {
                            case null -> "null";
                            case 1 -> "one";
                            case Integer j when j > 5 -> "big";
                            default -> "other";
                        };
                    }
                    public static String run() {
                        return s(null) + " " + s("a") + " " + s("b") + " " + s("c") + " " + s("z") + " "
                                + e(Color.RED) + " " + e(Color.GREEN) + " " + e(null) + " "
                                + i(1) + " " + i(9) + " " + i(3);
                    }
                }
                """));
    }

    @Test
    void aNullSelectorWithoutNullLabelThrows() throws Exception {
        assertEquals("npe npe ok", run("NullSelector", """
                package mini;
                public class NullSelector {
                    static String a(Object o) {
                        try {
                            return switch (o) {
                                case String s -> "s";
                                default -> "d";
                            };
                        } catch (NullPointerException e) {
                            return "npe";
                        }
                    }
                    static String b(Object o) {
                        try {
                            switch (o) {
                                case String s -> { return "s"; }
                                default -> { return "d"; }
                            }
                        } catch (NullPointerException e) {
                            return "npe";
                        }
                    }
                    public static String run() {
                        return a(null) + " " + b(null) + " " + (a("x").equals("s") ? "ok" : "bad");
                    }
                }
                """));
    }

    private java.util.List<String> errorsOf(final String className,
                                           final String body) throws Exception {
        final var srcDir = tempDir.resolve("src-" + className);
        final var javaFile = srcDir.resolve("mini/" + className + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package mini;
                public class %s {
                    sealed interface I permits A, B {}
                    record A() implements I {}
                    record B() implements I {}
                    record Pair(I x, I y) {}
                    enum Color { RED, GREEN }
                %s
                }
                """.formatted(className, body));
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SYSTEM, "C:\\projects\\nabu\\compiler\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, tempDir.resolve("out-" + className).toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen-" + className).toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .option(CompilerOption.RELEASE, "25")
                .build();
        final var compiler = new NabuCompiler();
        final var messages = new ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                messages.add(d.getMessage(Locale.ROOT).toString());
            }
        });
        compiler.compile(options);
        return messages;
    }

    private void assertNotExhaustive(final String name,
                                     final String body) throws Exception {
        final var errors = errorsOf(name, body);
        assertEquals(1, errors.size(), String.join("\n", errors));
        org.junit.jupiter.api.Assertions.assertTrue(errors.getFirst().contains("does not cover all possible input values"),
                errors.getFirst());
    }

    private void assertExhaustive(final String name,
                                  final String body) throws Exception {
        final var errors = errorsOf(name, body);
        assertEquals(0, errors.size(), String.join("\n", errors));
    }

    @Test
    void missingSealedSubtypeIsAnError() throws Exception {
        assertNotExhaustive("Ex1", """
                    static int f(I i) { return switch (i) { case A a -> 1; }; }
                """);
    }

    @Test
    void aGuardedPatternDoesNotCover() throws Exception {
        assertNotExhaustive("Ex2", """
                    static int f(I i) { return switch (i) { case A a -> 1; case B b when b != null -> 2; }; }
                """);
    }

    @Test
    void recordPatternsMustCoverTheComponents() throws Exception {
        assertNotExhaustive("Ex3", """
                    static int f(Pair p) {
                        return switch (p) {
                            case Pair(A a, I y) -> 1;
                            case Pair(B b, A y) -> 2;
                        };
                    }
                """);
        assertExhaustive("Ex4", """
                    static int f(Pair p) {
                        return switch (p) {
                            case Pair(A a, I y) -> 1;
                            case Pair(B b, A y) -> 2;
                            case Pair(B b, B y) -> 3;
                        };
                    }
                """);
    }

    @Test
    void aTypePatternOfTheSelectorTypeCoversEverything() throws Exception {
        assertExhaustive("Ex5", """
                    static int f(I i) { return switch (i) { case A a -> 1; case I other -> 2; }; }
                """);
    }

    @Test
    void aSwitchExpressionOverIntNeedsDefault() throws Exception {
        assertNotExhaustive("Ex6", """
                    static int f(int i) { return switch (i) { case 1 -> 1; case 2 -> 2; }; }
                """);
        assertExhaustive("Ex7", """
                    static int f(int i) { return switch (i) { case 1 -> 1; default -> 2; }; }
                """);
    }

    @Test
    void enumConstantsMustAllBeThere() throws Exception {
        assertNotExhaustive("Ex8", """
                    static int f(Color c) { return switch (c) { case RED -> 1; }; }
                """);
        assertExhaustive("Ex9", """
                    static int f(Color c) { return switch (c) { case RED -> 1; case GREEN -> 2; }; }
                """);
    }

    @Test
    void aSwitchStatementWithPatternsMustBeExhaustiveButAnOldOneNeedNotBe() throws Exception {
        assertNotExhaustive("Ex10", """
                    static void f(I i) { switch (i) { case A a -> { } } }
                """);
        assertExhaustive("Ex11", """
                    static void f(Color c) { switch (c) { case RED: break; } }
                    static void g(int i) { switch (i) { case 1: break; } }
                """);
    }

    @Test
    void qualifiedEnumConstantsForASealedSelector() throws Exception {
        assertEquals("c h j", run("Cards", """
                package mini;
                public class Cards {
                    sealed interface Card permits Suit, Joker {}
                    enum Suit implements Card { CLUBS, HEARTS }
                    record Joker() implements Card {}
                    static String f(Card c) {
                        return switch (c) {
                            case Suit.CLUBS -> "c";
                            case Suit.HEARTS -> "h";
                            case Joker j -> "j";
                        };
                    }
                    public static String run() {
                        return f(Suit.CLUBS) + " " + f(Suit.HEARTS) + " " + f(new Joker());
                    }
                }
                """));
    }

    @Test
    void qualifiedEnumConstantsMustCoverTheEnum() throws Exception {
        assertNotExhaustive("Ex12", """
                    sealed interface Card permits Suit, Joker {}
                    enum Suit implements Card { CLUBS, HEARTS }
                    record Joker() implements Card {}
                    static String f(Card c) {
                        return switch (c) {
                            case Suit.CLUBS -> "c";
                            case Joker j -> "j";
                        };
                    }
                """);
    }

    @Test
    void aSealedTypeWithoutPermitsPermitsTheSubtypesOfTheFile() throws Exception {
        assertEquals("circle 2 square 3 sealed 2", run("Implicit", """
                package mini;
                public class Implicit {
                    sealed interface Shape {}
                    record Circle(int r) implements Shape {}
                    record Square(int s) implements Shape {}
                    static String f(Shape shape) {
                        return switch (shape) {
                            case Circle c -> "circle " + c.r();
                            case Square s -> "square " + s.s();
                        };
                    }
                    public static String run() {
                        return f(new Circle(2)) + " " + f(new Square(3)) + " sealed "
                                + Shape.class.getPermittedSubclasses().length;
                    }
                }
                """));
    }

    @Test
    void aSealedTypeWithoutPermitsMustBeCovered() throws Exception {
        assertNotExhaustive("Ex13", """
                    sealed interface Shape {}
                    record Circle(int r) implements Shape {}
                    record Square(int s) implements Shape {}
                    static int f(Shape shape) {
                        return switch (shape) {
                            case Circle c -> 1;
                        };
                    }
                """);
    }

    @Test
    void everyPermittedSubtypeCountsNotOnlyTheLastOne() throws Exception {
        assertNotExhaustive("Ex14", """
                    sealed interface Base permits X, Y, Z {}
                    record X() implements Base {}
                    record Y() implements Base {}
                    record Z() implements Base {}
                    static int f(Base b) {
                        return switch (b) {
                            case X x -> 1;
                            case Z z -> 3;
                        };
                    }
                """);
        assertExhaustive("Ex15", """
                    sealed interface Base permits X, Y, Z {}
                    record X() implements Base {}
                    record Y() implements Base {}
                    record Z() implements Base {}
                    static int f(Base b) {
                        return switch (b) {
                            case X x -> 1;
                            case Y y -> 2;
                            case Z z -> 3;
                        };
                    }
                """);
    }
}
