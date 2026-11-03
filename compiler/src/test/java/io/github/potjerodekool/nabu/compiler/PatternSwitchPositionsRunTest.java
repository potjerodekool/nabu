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
 * A switch expression with patterns in every place where an expression can be: arguments, operands, fields,
 * lambda bodies, throw, yield, conditions and nested switches.
 */
// The classes are compiled for release 25 and loaded in this JVM.
@EnabledForJreRange(min = JRE.JAVA_25)
class PatternSwitchPositionsRunTest {

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

    private static final String HEAD = """
            package mini;
            public class %s {
                sealed interface Shape permits Circle, Square {}
                record Circle(int r) implements Shape {}
                record Square(int s) implements Shape {}
                static String name(Object o) {
                    return switch (o) {
                        case Circle c -> "circle";
                        case Square(int s) when s > 5 -> "big square";
                        case Square s -> "square";
                        default -> "other";
                    };
                }
                static String join(String a, String b) {
                    return a + "/" + b;
                }
            """;

    private Object runBody(final String className,
                           final String body) throws Exception {
        return run(className, HEAD.formatted(className) + body + "}\n");
    }

    @Test
    void methodArgument() throws Exception {
        assertEquals("x/circle", runBody("Arg1", """
                    static String describe(Object o) {
                        return join("x", switch (o) {
                            case Circle c -> "circle";
                            default -> "other";
                        });
                    }
                    public static String run() {
                        return describe(new Circle(1));
                    }
                """));
    }

    @Test
    void stringConcatenation() throws Exception {
        assertEquals("is circle!", runBody("Concat1", """
                    static String describe(Object o) {
                        return "is " + switch (o) {
                            case Circle c -> "circle";
                            default -> "other";
                        } + "!";
                    }
                    public static String run() {
                        return describe(new Circle(1));
                    }
                """));
    }

    @Test
    void arithmeticOperand() throws Exception {
        assertEquals(13, runBody("Arith1", """
                    static int area(Object o) {
                        return 2 * switch (o) {
                            case Circle(int r) -> r;
                            case Square(int s) -> s * 2;
                            default -> 0;
                        } + 1;
                    }
                    public static Integer run() {
                        return area(new Square(3));
                    }
                """));
    }

    @Test
    void expressionStatementArgument() throws Exception {
        assertEquals("circle", runBody("Stmt1", """
                    static String last = "";
                    static void remember(String s) {
                        last = s;
                    }
                    public static String run() {
                        Object o = new Circle(1);
                        remember(switch (o) {
                            case Circle c -> "circle";
                            default -> "other";
                        });
                        return last;
                    }
                """));
    }

    @Test
    void fieldInitializer() throws Exception {
        assertEquals("circle", runBody("Field1", """
                    static final Object SHAPE = new Circle(1);
                    static final String NAME = switch (SHAPE) {
                        case Circle c -> "circle";
                        default -> "other";
                    };
                    public static String run() {
                        return NAME;
                    }
                """));
    }

    @Test
    void instanceFieldInitializer() throws Exception {
        assertEquals("square", runBody("Field2", """
                    final Object shape;
                    final String label = switch (this.shape = new Square(2)) {
                        case Circle c -> "circle";
                        default -> "square";
                    };
                    public static String run() {
                        return new Field2().label;
                    }
                """));
    }

    @Test
    void throwStatement() throws Exception {
        assertEquals("boom circle", runBody("Throw1", """
                    static void fail(Object o) {
                        throw new IllegalStateException(switch (o) {
                            case Circle c -> "boom circle";
                            default -> "boom";
                        });
                    }
                    public static String run() {
                        try {
                            fail(new Circle(1));
                            return "no";
                        } catch (IllegalStateException e) {
                            return e.getMessage();
                        }
                    }
                """));
    }

    @Test
    void lambdaBody() throws Exception {
        assertEquals("circle other", runBody("Lambda1", """
                    public static String run() {
                        java.util.function.Function<Object, String> f = o -> switch (o) {
                            case Circle c -> "circle";
                            default -> "other";
                        };
                        return f.apply(new Circle(1)) + " " + f.apply("x");
                    }
                """));
    }

    @Test
    void nestedSwitch() throws Exception {
        assertEquals("circle:in square:out", runBody("Nested1", """
                    static String describe(Object o, Object p) {
                        return switch (o) {
                            case Circle c -> "circle:" + switch (p) {
                                case String s -> "in";
                                default -> "out";
                            };
                            case Square s -> "square:" + switch (p) {
                                case String t -> "in";
                                default -> "out";
                            };
                            default -> "other";
                        };
                    }
                    public static String run() {
                        return describe(new Circle(1), "x") + " " + describe(new Square(1), 5);
                    }
                """));
    }

    @Test
    void yieldInsideSwitch() throws Exception {
        assertEquals("c-in", runBody("Yield1", """
                    static String describe(Object o, Object p) {
                        return switch (o) {
                            case Circle c -> {
                                String inner = switch (p) {
                                    case String s -> "in";
                                    default -> "out";
                                };
                                yield "c-" + inner;
                            }
                            default -> "other";
                        };
                    }
                    public static String run() {
                        return describe(new Circle(1), "x");
                    }
                """));
    }

    @Test
    void selectorOfAnotherSwitch() throws Exception {
        assertEquals("circle!", runBody("Selector1", """
                    static String describe(Object o) {
                        return switch (name(o)) {
                            case "circle" -> "circle!";
                            default -> "other";
                        };
                    }
                    public static String run() {
                        return describe(new Circle(1));
                    }
                """));
    }

    @Test
    void ternaryAndComparisonOperands() throws Exception {
        assertEquals("true 1", runBody("Mixed1", """
                    static int code(Object o) {
                        return switch (o) {
                            case Circle c -> 1;
                            default -> 2;
                        };
                    }
                    public static String run() {
                        Object o = new Circle(1);
                        boolean same = code(o) == switch (o) {
                            case Circle c -> 1;
                            default -> 2;
                        };
                        int viaTernary = same ? switch (o) {
                            case Circle c -> 1;
                            default -> 2;
                        } : 0;
                        return same + " " + viaTernary;
                    }
                """));
    }

    @Test
    void evaluationOrderIsKept() throws Exception {
        assertEquals("a b c|a b", runBody("Order1", """
                    static StringBuilder log = new StringBuilder();
                    static String t(String s) {
                        log.append(s).append(' ');
                        return s;
                    }
                    static String f(String a, String b, String c) {
                        return a + " " + b + " " + c;
                    }
                    public static String run() {
                        Object o = new Circle(1);
                        String r = f(t("a"), switch (o) {
                            case Circle c -> t("b");
                            default -> "x";
                        }, t("c"));
                        return r + "|" + log.toString().trim().substring(0, 3);
                    }
                """));
    }

    @Test
    void conditionalRightOperandKeepsShortCircuit() throws Exception {
        assertEquals("false 0", runBody("Short1", """
                    static int calls = 0;
                    static String once(String s) {
                        calls++;
                        return s;
                    }
                    public static String run() {
                        Object o = new Circle(1);
                        boolean b = o instanceof Square && switch (o) {
                            case Circle c -> once("x").isEmpty();
                            default -> false;
                        };
                        return b + " " + calls;
                    }
                """));
    }

    @Test
    void castUnaryAndArrayIndex() throws Exception {
        assertEquals("7 -1 b", runBody("Misc1", """
                    static int pick(Object o) {
                        return switch (o) {
                            case Circle(int r) -> r;
                            default -> 0;
                        };
                    }
                    public static String run() {
                        Object o = new Circle(1);
                        long widened = (long) switch (o) {
                            case Circle(int r) -> r + 6;
                            default -> 0;
                        };
                        int negated = -switch (o) {
                            case Circle c -> 1;
                            default -> 0;
                        };
                        String[] names = {"a", "b", "c"};
                        String picked = names[switch (o) {
                            case Circle c -> 1;
                            default -> 0;
                        }];
                        return widened + " " + negated + " " + picked;
                    }
                """));
    }

    @Test
    void recordPatternInsideACaseBody() throws Exception {
        assertEquals("pair 3 other", runBody("Body1", """
                    record Pair(Object a, Object b) {}
                    static String describe(Object o) {
                        return switch (o) {
                            case Pair p -> {
                                if (p.a() instanceof Circle(int r) && p.b() instanceof Square(int s)) {
                                    yield "pair " + (r + s);
                                }
                                yield "other";
                            }
                            default -> "other";
                        };
                    }
                    public static String run() {
                        return describe(new Pair(new Circle(1), new Square(2))) + " " + describe(new Pair(1, 2));
                    }
                """));
    }

    @Test
    void loopConditionIsEvaluatedEveryRound() throws Exception {
        assertEquals("3", runBody("Loop1", """
                    public static String run() {
                        Object[] items = {new Circle(1), new Circle(2), new Circle(3), "stop"};
                        int i = 0;
                        int sum = 0;
                        while (switch (items[i]) {
                            case Circle c -> true;
                            default -> false;
                        }) {
                            sum++;
                            i++;
                        }
                        return "" + sum;
                    }
                """));
    }

    @Test
    void forConditionAndUpdate() throws Exception {
        assertEquals("0 1 2", runBody("For1", """
                    public static String run() {
                        StringBuilder sb = new StringBuilder();
                        Object[] items = {new Circle(0), new Circle(1), new Circle(2), "stop"};
                        for (int i = 0; switch (items[i]) { case Circle c -> true; default -> false; };
                             i += switch (items[i]) { case Circle c -> 1; default -> 0; }) {
                            if (sb.length() > 0) {
                                sb.append(' ');
                            }
                            sb.append(i);
                        }
                        return sb.toString();
                    }
                """));
    }

    @Test
    void recordPatternInstanceofAsArgument() throws Exception {
        assertEquals("true false", runBody("Inst1", """
                    static String show(boolean b) {
                        return String.valueOf(b);
                    }
                    public static String run() {
                        Object a = new Circle(4);
                        Object b = new Square(4);
                        return show(a instanceof Circle(int r)) + " " + show(b instanceof Circle(int r));
                    }
                """));
    }

    @Test
    void bindingsAreUsableInTheSameExpression() throws Exception {
        assertEquals("true false", runBody("Inst2", """
                    static String show(boolean b) {
                        return String.valueOf(b);
                    }
                    public static String run() {
                        Object a = new Circle(4);
                        Object b = new Circle(1);
                        return show(a instanceof Circle(int r) && r > 3) + " " + show(b instanceof Circle(int r) && r > 3);
                    }
                """));
    }

    @Test
    void recordPatternInConcatenationAndLambdaAndThrow() throws Exception {
        assertEquals("is circle lc lo boom", runBody("Inst3", """
                    public static String run() {
                        Object o = new Circle(1);
                        String text = "is " + (o instanceof Circle(int r) ? "circle" : "other");
                        java.util.function.Function<Object, String> f = x -> (x instanceof Circle(int r)) ? "lc" : "lo";
                        String thrown;
                        try {
                            throw new IllegalStateException(o instanceof Circle(int r) ? "boom" : "no");
                        } catch (IllegalStateException e) {
                            thrown = e.getMessage();
                        }
                        return text + " " + f.apply(o) + " " + f.apply("x") + " " + thrown;
                    }
                """));
    }

    @Test
    void recordPatternInForConditionIsEvaluatedEveryRound() throws Exception {
        assertEquals("3", runBody("Inst4", """
                    public static String run() {
                        Object[] items = {new Circle(1), new Circle(2), new Circle(3), "stop"};
                        int count = 0;
                        for (int i = 0; java.util.Objects.equals(true, items[i] instanceof Circle(int r)); i++) {
                            count++;
                        }
                        return "" + count;
                    }
                """));
    }

    @Test
    void recordPatternInTheBranchOfAnIfWithoutBraces() throws Exception {
        assertEquals("a b", runBody("Inst5", """
                    static String show(boolean b) {
                        return b ? "a" : "b";
                    }
                    public static String run() {
                        Object o = new Circle(1);
                        String r = "";
                        if (o != null)
                            r = show(o instanceof Circle(int q));
                        if (o == null)
                            r = "never";
                        else
                            r = r + " " + show(o instanceof Square(int q));
                        return r;
                    }
                """));
    }
}
