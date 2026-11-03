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
 * Lowerings (boxing, unboxing, implicit this, for-each, try/finally, string and enum switches) in places that are
 * deep in a tree: arguments of arguments, operands of operands, lambda bodies, case bodies, anonymous classes.
 */
class LowerDepthRunTest {

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

    @Test
    void boxingOfAnArgumentThatIsAnArgument() throws Exception {
        assertEquals("7", run("Box1", """
                    static Integer inc(Integer i) {
                        return i + 1;
                    }
                    static String text(Object o) {
                        return String.valueOf(o);
                    }
                    public static String run() {
                        return text(inc(inc(5)));
                    }
                """));
    }

    @Test
    void unboxingInNestedOperands() throws Exception {
        assertEquals("14", run("Unbox1", """
                    public static String run() {
                        Integer a = 2;
                        Integer b = 3;
                        Integer c = 4;
                        int r = (a + b) * c - (a * b);
                        return String.valueOf(r);
                    }
                """));
    }

    @Test
    void unboxingInsideAnArgumentOfAnArgument() throws Exception {
        assertEquals("9", run("Unbox2", """
                    static int twice(int i) {
                        return i * 2;
                    }
                    static String text(Object o) {
                        return String.valueOf(o);
                    }
                    public static String run() {
                        Integer a = 4;
                        Integer b = 1;
                        return text(twice(a) + b);
                    }
                """));
    }

    @Test
    void wideningInNestedOperands() throws Exception {
        assertEquals("7.5", run("Widen1", """
                    static String text(Object o) {
                        return String.valueOf(o);
                    }
                    public static String run() {
                        int a = 3;
                        long b = 4;
                        double c = 0.5;
                        return text((a + b) + c);
                    }
                """));
    }

    @Test
    void implicitThisInArguments() throws Exception {
        assertEquals("3 6", run("This1", """
                    int value = 3;
                    int twice(int i) {
                        return i * 2;
                    }
                    String show(Object a, Object b) {
                        return a + " " + b;
                    }
                    String go() {
                        return show(value, twice(value));
                    }
                    public static String run() {
                        return new This1().go();
                    }
                """));
    }

    @Test
    void implicitThisInOperandsAndCasts() throws Exception {
        assertEquals("11", run("This2", """
                    int value = 5;
                    int go() {
                        return (int) ((long) value + (value + 1));
                    }
                    public static String run() {
                        return String.valueOf(new This2().go());
                    }
                """));
    }

    @Test
    void forEachInALambdaBody() throws Exception {
        assertEquals("6", run("Each1", """
                    public static String run() {
                        java.util.function.Supplier<Integer> sum = () -> {
                            int total = 0;
                            for (int x : java.util.List.of(1, 2, 3)) {
                                total += x;
                            }
                            return total;
                        };
                        return String.valueOf(sum.get());
                    }
                """));
    }

    @Test
    void forEachInACaseBodyOfASwitchStatement() throws Exception {
        assertEquals("6", run("Each2", """
                    public static String run() {
                        int total = 0;
                        int mode = 1;
                        switch (mode) {
                            case 1 -> {
                                for (int x : java.util.List.of(1, 2, 3)) {
                                    total += x;
                                }
                            }
                            default -> total = -1;
                        }
                        return String.valueOf(total);
                    }
                """));
    }

    @Test
    void tryFinallyInALambdaBody() throws Exception {
        assertEquals("ok fin", run("Try1", """
                    static StringBuilder log = new StringBuilder();
                    public static String run() {
                        java.util.function.Supplier<String> s = () -> {
                            try {
                                return "ok";
                            } finally {
                                log.append("fin");
                            }
                        };
                        return s.get() + " " + log;
                    }
                """));
    }

    @Test
    void tryFinallyInACaseBody() throws Exception {
        assertEquals("a-fin", run("Try2", """
                    static StringBuilder log = new StringBuilder();
                    public static String run() {
                        int mode = 1;
                        switch (mode) {
                            case 1 -> {
                                try {
                                    log.append("a");
                                } finally {
                                    log.append("-fin");
                                }
                            }
                            default -> log.append("none");
                        }
                        return log.toString();
                    }
                """));
    }

    @Test
    void stringSwitchInALambdaBody() throws Exception {
        assertEquals("1 2 0", run("StrSwitch1", """
                    public static String run() {
                        java.util.function.Function<String, Integer> f = s -> {
                            switch (s) {
                                case "a": return 1;
                                case "b": return 2;
                                default: return 0;
                            }
                        };
                        return f.apply("a") + " " + f.apply("b") + " " + f.apply("z");
                    }
                """));
    }

    @Test
    void enumSwitchStatementInACaseBody() throws Exception {
        assertEquals("green", run("EnumSwitch1", """
                    enum Color { RED, GREEN }
                    public static String run() {
                        int mode = 1;
                        String result = "";
                        switch (mode) {
                            case 1 -> {
                                Color c = Color.GREEN;
                                switch (c) {
                                    case RED -> result = "red";
                                    case GREEN -> result = "green";
                                }
                            }
                            default -> result = "none";
                        }
                        return result;
                    }
                """));
    }

    @Test
    void stringAndEnumSwitchExpressionsAsArguments() throws Exception {
        assertEquals("two green", run("SwitchExpr1", """
                    enum Color { RED, GREEN }
                    static String join(String a, String b) {
                        return a + " " + b;
                    }
                    public static String run() {
                        String s = "b";
                        Color c = Color.GREEN;
                        return join(switch (s) {
                            case "a" -> "one";
                            case "b" -> "two";
                            default -> "many";
                        }, switch (c) {
                            case RED -> "red";
                            case GREEN -> "green";
                        });
                    }
                """));
    }

    @Test
    void conditionalConversionsInNestedPositions() throws Exception {
        assertEquals("3 4.5", run("Cond1", """
                    static String text(Object o) {
                        return String.valueOf(o);
                    }
                    public static String run() {
                        boolean b = true;
                        Integer boxed = 3;
                        return text(b ? boxed : 0) + " " + text((b ? 1 : 2.5) + 3.5);
                    }
                """));
    }

    @Test
    void lowersInAnAnonymousClassInsideAnArgument() throws Exception {
        assertEquals("6", run("Anon1", """
                    static String text(Object o) {
                        return String.valueOf(o);
                    }
                    public static String run() {
                        return text(new java.util.function.Supplier<Integer>() {
                            public Integer get() {
                                int total = 0;
                                for (int x : java.util.List.of(1, 2, 3)) {
                                    total += x;
                                }
                                return total;
                            }
                        }.get());
                    }
                """));
    }

    @Test
    void forEachInALambdaThatIsAnArgument() throws Exception {
        assertEquals("6", run("LamArg1", """
                    static int apply(java.util.function.Supplier<Integer> s) {
                        return s.get();
                    }
                    public static String run() {
                        return String.valueOf(apply(() -> {
                            int total = 0;
                            for (int x : java.util.List.of(1, 2, 3)) {
                                total += x;
                            }
                            return total;
                        }));
                    }
                """));
    }

    @Test
    void tryFinallyInALambdaThatIsAnArgument() throws Exception {
        assertEquals("ok fin", run("LamArg2", """
                    static StringBuilder log = new StringBuilder();
                    static String apply(java.util.function.Supplier<String> s) {
                        return s.get();
                    }
                    public static String run() {
                        String r = apply(() -> {
                            try {
                                return "ok";
                            } finally {
                                log.append("fin");
                            }
                        });
                        return r + " " + log;
                    }
                """));
    }

    @Test
    void stringSwitchInALambdaThatIsAnArgument() throws Exception {
        assertEquals("2", run("LamArg3", """
                    static int apply(java.util.function.Supplier<Integer> s) {
                        return s.get();
                    }
                    public static String run() {
                        String key = "b";
                        return String.valueOf(apply(() -> {
                            switch (key) {
                                case "a": return 1;
                                case "b": return 2;
                                default: return 0;
                            }
                        }));
                    }
                """));
    }

    @Test
    void lambdaInsideALambdaInsideAnArgument() throws Exception {
        assertEquals("6", run("LamArg4", """
                    static int apply(java.util.function.Supplier<java.util.function.Supplier<Integer>> s) {
                        return s.get().get();
                    }
                    public static String run() {
                        return String.valueOf(apply(() -> () -> {
                            int total = 0;
                            for (int x : java.util.List.of(1, 2, 3)) {
                                total += x;
                            }
                            return total;
                        }));
                    }
                """));
    }

    @Test
    void forEachInALambdaPassedToAConstructor() throws Exception {
        assertEquals("6", run("LamNew1", """
                    static int total;
                    public static String run() throws Exception {
                        Thread t = new Thread(() -> {
                            for (int x : java.util.List.of(1, 2, 3)) {
                                total += x;
                            }
                        });
                        t.start();
                        t.join();
                        return String.valueOf(total);
                    }
                """));
    }

    @Test
    void forEachInALambdaThatIsReturned() throws Exception {
        assertEquals("6", run("LamRet1", """
                    static java.util.function.Supplier<Integer> make() {
                        return () -> {
                            int total = 0;
                            for (int x : java.util.List.of(1, 2, 3)) {
                                total += x;
                            }
                            return total;
                        };
                    }
                    public static String run() {
                        return String.valueOf(make().get());
                    }
                """));
    }

    @Test
    void forEachInALambdaInATernary() throws Exception {
        assertEquals("6", run("LamTern1", """
                    public static String run() {
                        boolean b = true;
                        java.util.function.Supplier<Integer> s = b ? () -> {
                            int total = 0;
                            for (int x : java.util.List.of(1, 2, 3)) {
                                total += x;
                            }
                            return total;
                        } : () -> 0;
                        return String.valueOf(s.get());
                    }
                """));
    }

    @Test
    void forEachInALambdaInAnArrayInitializer() throws Exception {
        assertEquals("6", run("LamArr1", """
                    @SuppressWarnings("unchecked")
                    public static String run() {
                        java.util.function.Supplier<Integer>[] all = new java.util.function.Supplier[]{() -> {
                            int total = 0;
                            for (int x : java.util.List.of(1, 2, 3)) {
                                total += x;
                            }
                            return total;
                        }};
                        return String.valueOf(all[0].get());
                    }
                """));
    }

    @Test
    void forEachInALambdaOfAFieldInitializer() throws Exception {
        assertEquals("6", run("LamField1", """
                    static final java.util.function.Supplier<Integer> SUM = () -> {
                        int total = 0;
                        for (int x : java.util.List.of(1, 2, 3)) {
                            total += x;
                        }
                        return total;
                    };
                    public static String run() {
                        return String.valueOf(SUM.get());
                    }
                """));
    }

    @Test
    void forEachInTheArgumentOfAnArgumentOfAnAnonymousClass() throws Exception {
        assertEquals("6", run("LamAnon1", """
                    static String text(Object o) {
                        return String.valueOf(o);
                    }
                    static int apply(java.util.function.Supplier<Integer> s) {
                        return s.get();
                    }
                    public static String run() {
                        return text(apply(new java.util.function.Supplier<Integer>() {
                            public Integer get() {
                                int total = 0;
                                for (int x : java.util.List.of(1, 2, 3)) {
                                    total += x;
                                }
                                return total;
                            }
                        }));
                    }
                """));
    }

    @Test
    void forEachInAnExpressionLambdaThatCallsALambdaWithABlock() throws Exception {
        assertEquals("6", run("LamChain1", """
                    static int apply(java.util.function.Supplier<Integer> s) {
                        return s.get();
                    }
                    public static String run() {
                        java.util.function.Supplier<Integer> outer = () -> apply(() -> {
                            int total = 0;
                            for (int x : java.util.List.of(1, 2, 3)) {
                                total += x;
                            }
                            return total;
                        });
                        return String.valueOf(outer.get());
                    }
                """));
    }

    @Test
    void callOnAnElementOfAnArrayOfAGenericInterface() throws Exception {
        assertEquals("6", run("ArrCall1", """
                    @SuppressWarnings("unchecked")
                    public static String run() {
                        java.util.function.Supplier<Integer> one = new java.util.function.Supplier<>() {
                            public Integer get() {
                                return 6;
                            }
                        };
                        java.util.function.Supplier<Integer>[] all = new java.util.function.Supplier[]{one};
                        return String.valueOf(all[0].get());
                    }
                """));
    }

    @Test
    void lambdasInAConditionalThatIsAssigned() throws Exception {
        assertEquals("1 2", run("LamAssign1", """
                    public static String run() {
                        java.util.function.Supplier<Integer> s;
                        s = true ? () -> 1 : () -> 2;
                        java.util.function.Supplier<Integer> t;
                        t = false ? () -> 1 : () -> 2;
                        return s.get() + " " + t.get();
                    }
                """));
    }

    @Test
    void lambdasInAConditionalThatIsAnArgument() throws Exception {
        assertEquals("1 2", run("LamCondArg1", """
                    static int apply(java.util.function.Supplier<Integer> s) {
                        return s.get();
                    }
                    public static String run() {
                        boolean b = true;
                        return apply(b ? () -> 1 : () -> 2) + " " + apply(!b ? () -> 1 : () -> 2);
                    }
                """));
    }

    @Test
    void aLambdaThatIsAssignedToAVariableAndAField() throws Exception {
        assertEquals("1 2", run("LamAssign2", """
                    static java.util.function.Supplier<Integer> field;
                    public static String run() {
                        java.util.function.Supplier<Integer> s;
                        s = () -> 1;
                        field = () -> 2;
                        return s.get() + " " + field.get();
                    }
                """));
    }
}
