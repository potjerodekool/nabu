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
 * Derde ronde: Java 21 patroonherkenning in switch (typepatronen, case null,
 * when-guards en recordpatronen). Zelfde opzet als {@link FrontendGapsRunTest}
 * en {@link FrontendGaps2RunTest}.
 */
class FrontendGaps3RunTest {

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
    void typePatternWithDefault() throws Exception {
        assertEquals("str hi int 7 other", run("""
                Object a = "hi";
                Object b = 7;
                Object c = true;
                String ra;
                switch (a) {
                    case Integer i -> ra = "int " + i;
                    case String s -> ra = "str " + s;
                    default -> ra = "other";
                }
                String rb;
                switch (b) {
                    case Integer i -> rb = "int " + i;
                    case String s -> rb = "str " + s;
                    default -> rb = "other";
                }
                String rc;
                switch (c) {
                    case Integer i -> rc = "int " + i;
                    case String s -> rc = "str " + s;
                    default -> rc = "other";
                }
                return ra + " " + rb + " " + rc;"""));
    }

    @Test
    void colonStyleTypePatternWithBreak() throws Exception {
        assertEquals("int 42", run("""
                Object o = 42;
                String r;
                switch (o) {
                    case Integer i:
                        r = "int " + i;
                        break;
                    default:
                        r = "other";
                        break;
                }
                return r;"""));
    }

    @Test
    void caseNullLabel() throws Exception {
        assertEquals("null other null", run("""
                String ra;
                switch (nil()) {
                    case null -> ra = "null";
                    default -> ra = "other";
                }
                String rb;
                switch (val("x")) {
                    case null -> rb = "null";
                    default -> rb = "other";
                }
                String rc;
                switch (nil()) {
                    case null -> rc = "null";
                    default -> rc = "null";
                }
                return ra + " " + rb + " " + rc;""",
                """
                static Object nil() { return null; }
                static Object val(Object o) { return o; }
                """));
    }

    @Test
    void whenGuard() throws Exception {
        assertEquals("big small", run("""
                Object a = 10;
                Object b = 1;
                String ra;
                switch (a) {
                    case Integer i when i > 5 -> ra = "big";
                    case Integer i -> ra = "small";
                    default -> ra = "other";
                }
                String rb;
                switch (b) {
                    case Integer i when i > 5 -> rb = "big";
                    case Integer i -> rb = "small";
                    default -> rb = "other";
                }
                return ra + " " + rb;"""));
    }

    @Test
    void whenGuardOnTypePattern() throws Exception {
        assertEquals("yes no no", run("""
                String a = "abc";
                String b = "x";
                String c = "hello";
                String ra;
                switch (a) {
                    case String s when s.length() == 3 -> ra = "yes";
                    default -> ra = "no";
                }
                String rb;
                switch (b) {
                    case String s when s.length() == 3 -> rb = "yes";
                    default -> rb = "no";
                }
                String rc;
                switch (c) {
                    case String s when s.length() == 3 -> rc = "yes";
                    default -> rc = "no";
                }
                return ra + " " + rb + " " + rc;"""));
    }

    @Test
    void recordPattern() throws Exception {
        assertEquals("p 3 4 none", run("""
                Object a = new Point(3, 4);
                Object b = "x";
                String ra;
                switch (a) {
                    case Point(int x, int y) -> ra = "p " + x + " " + y;
                    default -> ra = "none";
                }
                String rb;
                switch (b) {
                    case Point(int x, int y) -> rb = "p " + x + " " + y;
                    default -> rb = "none";
                }
                return ra + " " + rb;""",
                """
                record Point(int x, int y) {}
                """));
    }

    @Test
    void recordPatternWithGuardAndNull() throws Exception {
        assertEquals("near far none null", run("""
                Object a = new Point(1, 1);
                Object b = new Point(9, 9);
                Object c = "x";
                String ra;
                switch (a) {
                    case Point(int x, int y) when x + y < 5 -> ra = "near";
                    case Point(int x, int y) -> ra = "far";
                    default -> ra = "none";
                }
                String rb;
                switch (b) {
                    case Point(int x, int y) when x + y < 5 -> rb = "near";
                    case Point(int x, int y) -> rb = "far";
                    default -> rb = "none";
                }
                String rc;
                switch (c) {
                    case Point(int x, int y) when x + y < 5 -> rc = "near";
                    case Point(int x, int y) -> rc = "far";
                    default -> rc = "none";
                }
                Object d = null;
                String rd;
                switch (d) {
                    case Point(int x, int y) -> rd = "p";
                    case null, default -> rd = "null";
                }
                return ra + " " + rb + " " + rc + " " + rd;""",
                """
                record Point(int x, int y) {}
                """));
    }

    @Test
    void nestedRecordPattern() throws Exception {
        assertEquals("in out none", run("""
                Object a = new Box(new Point(1, 2));
                Object b = new Box(new Point(9, 9));
                Object c = "x";
                String ra;
                switch (a) {
                    case Box(Point(int x, int y)) when x < 5 -> ra = "in";
                    case Box(Point(int x, int y)) -> ra = "out";
                    default -> ra = "none";
                }
                String rb;
                switch (b) {
                    case Box(Point(int x, int y)) when x < 5 -> rb = "in";
                    case Box(Point(int x, int y)) -> rb = "out";
                    default -> rb = "none";
                }
                String rc;
                switch (c) {
                    case Box(Point(int x, int y)) -> rc = "p";
                    default -> rc = "none";
                }
                return ra + " " + rb + " " + rc;""",
                """
                record Point(int x, int y) {}
                record Box(Point p) {}
                """));
    }

    @Test
    void patternBindingVisibleInBody() throws Exception {
        assertEquals("len 5", run("""
                Object o = "hello";
                int size = 0;
                switch (o) {
                    case String s -> size = s.length();
                    default -> size = -1;
                }
                return "len " + size;"""));
    }
}
