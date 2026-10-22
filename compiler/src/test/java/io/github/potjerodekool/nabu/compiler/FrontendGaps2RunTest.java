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
 * Tweede ronde: Java-taalfeatures die de frontend nog niet ondersteunt.
 * Zelfde opzet als {@link FrontendGapsRunTest}: compileren met de ASM-backend
 * en het resultaat op de JVM uitvoeren.
 */
class FrontendGaps2RunTest {

    @TempDir
    Path tempDir;

    private String run(final String body) throws Exception {
        return run(body, "");
    }

    private String run(final String body, final String members) throws Exception {
        final var srcDir = tempDir.resolve("src");
        final var javaFile = srcDir.resolve("mini/Gap.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, "package mini;\n"
                + "import java.util.*;\n"
                + "public class Gap {\n"
                + members + "\n"
                + "    public static String run() throws Exception {\n"
                + body + "\n"
                + "    }\n"
                + "}\n");

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

        try (var loader = new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()}, getClass().getClassLoader())) {
            return (String) loader.loadClass("mini.Gap").getMethod("run").invoke(null);
        }
    }

    @Test
    void switchExpressionWithYield() throws Exception {
        assertEquals("big small", run("""
                int day = 13;
                String size = switch (day) {
                    case 13 -> { yield "big"; }
                    default -> "small";
                };
                int other = 1;
                String sizeOther = switch (other) {
                    case 13 -> { yield "big"; }
                    default -> "small";
                };
                return size + " " + sizeOther;"""));
    }

    @Test
    void switchExpressionOldSyntax() throws Exception {
        assertEquals("big small", run("""
                int day = 13;
                String size = switch (day) {
                    case 13: yield "big";
                    default: yield "small";
                };
                int other = 1;
                String sizeOther = switch (other) {
                    case 13: yield "big";
                    default: yield "small";
                };
                return size + " " + sizeOther;"""));
    }

    @Test
    void textBlock() throws Exception {
        assertEquals("a\nb", run("""
                String s = \"\"\"
                        a
                        b\"\"\";
                return s;"""));
    }

    @Test
    void varLocal() throws Exception {
        assertEquals("3 hello", run("""
                var n = 3;
                var s = "hello";
                return n + " " + s;"""));
    }

    @Test
    void methodReference() throws Exception {
        assertEquals("HELLO", run("""
                java.util.function.Function<String, String> f = String::toUpperCase;
                return f.apply("hello");"""));
    }

    @Test
    void methodReferenceBound() throws Exception {
        assertEquals("3", run("""
                String s = "abc";
                java.util.function.Supplier<Integer> len = s::length;
                return String.valueOf(len.get());"""));
    }

    @Test
    void lambdaWithBlockBody() throws Exception {
        assertEquals("42", run("""
                java.util.function.IntSupplier s = () -> {
                    int x = 40;
                    return x + 2;
                };
                return String.valueOf(s.getAsInt());"""));
    }

    @Test
    void innerClassAccessesOuterField() throws Exception {
        assertEquals("7", run("""
                class Outer {
                    int value = 7;
                    class Inner {
                        int get() { return value; }
                    }
                    int viaInner() { return new Inner().get(); }
                }
                return String.valueOf(new Outer().viaInner());"""));
    }

    @Test
    void anonymousClass() throws Exception {
        assertEquals("hi 5", run("""
                java.util.function.Supplier<String> s = new java.util.function.Supplier<String>() {
                    @Override
                    public String get() { return "hi"; }
                };
                return s.get() + " " + 5;"""));
    }

    @Test
    void enumWithConstructorAndMethod() throws Exception {
        assertEquals("RED 0 GREEN 1", run("""
                Color c = Color.RED;
                Color d = Color.GREEN;
                return c + " " + c.ordinal() + " " + d + " " + d.ordinal();""",
                "enum Color {\n"
                        + "    RED(1), GREEN(2);\n"
                        + "    final int code;\n"
                        + "    Color(int code) { this.code = code; }\n"
                        + "}"));
    }

    @Test
    void enumWithAbstractMethod() throws Exception {
        assertEquals("r g", run("""
                return Color.RED.label() + " " + Color.GREEN.label();""",
                "enum Color {\n"
                        + "    RED { String label() { return \"r\"; } },\n"
                        + "    GREEN { String label() { return \"g\"; } };\n"
                        + "    abstract String label();\n"
                        + "}"));
    }

    @Test
    void interfaceDefaultMethod() throws Exception {
        assertEquals("hi default", run("""
                Greeting g = new Greeting() { };
                return g.hello() + " " + g.bye();""",
                "interface Greeting {\n"
                        + "    default String hello() { return \"hi\"; }\n"
                        + "    default String bye() { return \"default\"; }\n"
                        + "}"));
    }

    @Test
    void multiCatch() throws Exception {
        assertEquals("caught", run("""
                try {
                    throw new java.io.IOException("x");
                } catch (java.io.IOException | java.lang.RuntimeException e) {
                    return "caught";
                }"""));
    }

    @Test
    void labeledBreak() throws Exception {
        assertEquals("0", run("""
                StringBuilder sb = new StringBuilder();
                outer:
                for (int i = 0; i < 3; i++) {
                    sb.append(i).append(" ");
                    for (int j = 0; j < 3; j++) {
                        if (j == 1) {
                            break outer;
                        }
                    }
                }
                return sb.toString().trim();"""));
    }

    @Test
    void labeledContinue() throws Exception {
        assertEquals("0 0 1 1 2 2", run("""
                StringBuilder sb = new StringBuilder();
                outer:
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        if (j == 2) {
                            continue outer;
                        }
                        sb.append(i).append(" ");
                    }
                }
                return sb.toString().trim();"""));
    }

    @Test
    void staticInitializerBlock() throws Exception {
        assertEquals("42", run("""
                return String.valueOf(VALUE);""",
                "static int VALUE;\n"
                        + "static { VALUE = 42; }"));
    }

    @Test
    void instanceInitializerBlock() throws Exception {
        assertEquals("1", run("""
                return String.valueOf(new C().value);""",
                "static class C {\n"
                        + "    int value;\n"
                        + "    { value = 1; }\n"
                        + "}"));
    }

    @Test
    void enhancedForOverList() throws Exception {
        assertEquals("1 2 3", run("""
                StringBuilder sb = new StringBuilder();
                for (int i : java.util.List.of(1, 2, 3)) {
                    sb.append(i).append(" ");
                }
                return sb.toString().trim();"""));
    }

    @Test
    void enhancedForOverArray() throws Exception {
        assertEquals("a b", run("""
                StringBuilder sb = new StringBuilder();
                for (String s : new String[] { "a", "b" }) {
                    sb.append(s).append(" ");
                }
                return sb.toString().trim();"""));
    }

    @Test
    void interfaceInheritance() throws Exception {
        assertEquals("a", run("""
                C c = new C();
                return c.hello();""",
                "interface A extends B {\n"
                        + "}\n"
                        + "interface B {\n"
                        + "    default String hello() { return \"a\"; }\n"
                        + "}\n"
                        + "static class C implements A {\n"
                        + "}"));
    }

    @Test
    void genericMethod() throws Exception {
        assertEquals("hello", run("""
                return identity("hello");""",
                "static <T> T identity(T t) { return t; }"));
    }

    @Test
    void nestedGenericCall() throws Exception {
        assertEquals("3", run("""
                return String.valueOf(size(java.util.List.of("a", "b", "c")));""",
                "static <T> int size(java.util.List<T> list) { return list.size(); }"));
    }

    @Test
    void arraysOfGenerics() throws Exception {
        assertEquals("2", run("""
                String[] names = new String[2];
                names[0] = "a";
                names[1] = "b";
                return String.valueOf(names.length);"""));
    }

    @Test
    void instanceofPattern() throws Exception {
        assertEquals("yes", run("""
                Object o = "text";
                if (o instanceof String s) {
                    return "yes";
                }
                return "no";"""));
    }

    @Test
    void castToGeneric() throws Exception {
        assertEquals("2", run("""
                java.util.List<?> list = java.util.List.of("a", "b");
                return String.valueOf(list.size());"""));
    }

    @Test
    void conditionalExpression() throws Exception {
        assertEquals("big", run("""
                int n = 10;
                return n > 5 ? "big" : "small";"""));
    }

    @Test
    void stringBuilderChain() throws Exception {
        assertEquals("a-b", run("""
                return new StringBuilder().append("a").append("-").append("b").toString();"""));
    }

    @Test
    void doWhileLoop() throws Exception {
        assertEquals("5", run("""
                int i = 0;
                do {
                    i++;
                } while (i < 5);
                return String.valueOf(i);"""));
    }

    @Test
    void nestedClassesInInterface() throws Exception {
        assertEquals("x", run("""
                return Holder.VALUE;""",
                "interface Holder {\n"
                        + "    String VALUE = \"x\";\n"
                        + "}"));
    }

    @Test
    void staticMethodInEnum() throws Exception {
        assertEquals("red", run("""
                return Color.first();""",
                "enum Color {\n"
                        + "    RED, GREEN;\n"
                        + "    static String first() { return values()[0].name().toLowerCase(); }\n"
                        + "}"));
    }
}
