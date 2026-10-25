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
 * Java language features that the front end did not support, found by running JDK-style code natively. Each
 * program is compiled with the ASM backend and run on the JVM; {@code run()} returns what it computed.
 */
class FrontendGapsRunTest {

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
    void switchOnString() throws Exception {
        assertEquals("2 3 1 9", run("""
                        StringBuilder sb = new StringBuilder();
                        String[] words = {"b", "c", "a", "z"};
                        for (int i = 0; i < words.length; i++) {
                            switch (words[i]) {
                                case "a":
                                    sb.append(1);
                                    break;
                                case "b":
                                    sb.append(2);
                                    break;
                                case "c":
                                    sb.append(3);
                                    break;
                                default:
                                    sb.append(9);
                            }
                            if (i < words.length - 1) {
                                sb.append(' ');
                            }
                        }
                        return sb.toString();"""));
    }

    @Test
    void shiftOperators() throws Exception {
        assertEquals("40 -3 536870910 1 -4294967296 4611686018427387903", run("""
                        int a = 5 << 3;
                        int b = -12 >> 2;
                        int c = -12 >>> 3;
                        int d = 1 << 32;
                        long e = -1L << 32;
                        long f = -1L >>> 2;
                        return a + " " + b + " " + c + " " + d + " " + e + " " + f;"""));
    }

    @Test
    void recordMethods() throws Exception {
        assertEquals("true false 33 Point[x=1, y=2]", run("""
                        Point p = new Point(1, 2);
                        return p.equals(new Point(1, 2)) + " " + p.equals(new Point(2, 2)) + " " + p.hashCode()
                                + " " + p;""", "record Point(int x, int y) { }"));
    }

    @Test
    void boxingOnAssignmentAndInitializers() throws Exception {
        assertEquals("5 6 2 30", run("""
                        Object o = 5;
                        Integer[] boxed = {6, 7};
                        List<Integer> list = new ArrayList<>();
                        list.add(8);
                        long seed = 42;
                        Random random = new Random(42);
                        return o + " " + boxed[0] + " " + (list.size() + 1) + " " + random.nextInt(100);"""));
    }

    @Test
    void listIsNotBytes() throws Exception {
        assertEquals("lines", run("""
                        return pick(Arrays.asList("a", "b"));""",
                "static String pick(Iterable<? extends CharSequence> lines) { return \"lines\"; }\n"
                        + "static String pick(byte[] bytes) { return \"bytes\"; }"));
    }

    @Test
    void overloadWithVariable() throws Exception {
        assertEquals("lines", run("""
                        List<String> l = new ArrayList<>();
                        return pick(l);""",
                "static String pick(Iterable<? extends CharSequence> lines) { return \"lines\"; } "
                        + "static String pick(byte[] bytes) { return \"bytes\"; }"));
    }

    @Test
    void overloadWithGenericCall() throws Exception {
        assertEquals("lines", run("""
                        return pick(Collections.singletonList("a"));""",
                "static String pick(Iterable<? extends CharSequence> lines) { return \"lines\"; } "
                        + "static String pick(byte[] bytes) { return \"bytes\"; }"));
    }

    @Test
    void overloadWithPlainIterable() throws Exception {
        assertEquals("lines", run("""
                        return pick(Arrays.asList("a", "b"));""",
                "static String pick(Iterable<String> lines) { return \"lines\"; } "
                        + "static String pick(byte[] bytes) { return \"bytes\"; }"));
    }

    @Test
    void asListOnlyIterable() throws Exception {
        assertEquals("lines", run("""
                        return pick(Arrays.asList("a", "b"));""",
                "static String pick(Iterable<String> lines) { return \"lines\"; }"));
    }

    @Test
    void userVarargsWithTwoOverloads() throws Exception {
        assertEquals("lines", run("""
                        return pick(list("a", "b"));""",
                "static List<String> list(String... values) { return Arrays.asList(values); } "
                        + "static String pick(Iterable<String> lines) { return \"lines\"; } "
                        + "static String pick(byte[] bytes) { return \"bytes\"; }"));
    }

    @Test
    void lambdaParametersOfGenericCalls() throws Exception {
        assertEquals("[delta, charlie]", run("""
                        List<String> words = Arrays.asList("delta", "alpha", "charlie");
                        return words.stream().filter(w -> w.length() > 5 || w.startsWith("d"))
                                .collect(java.util.stream.Collectors.toList()).toString();"""));
    }

    @Test
    void tryWithResourcesVariable() throws Exception {
        assertEquals("body closed", run("""
                        StringBuilder log = new StringBuilder();
                        try (Res res = new Res(log)) {
                            res.touch();
                        }
                        return log.toString();""",
                "static class Res implements AutoCloseable {\n"
                        + "    final StringBuilder log;\n"
                        + "    Res(StringBuilder log) { this.log = log; }\n"
                        + "    void touch() { log.append(\"body\"); }\n"
                        + "    public void close() { log.append(\" closed\"); }\n"
                        + "}"));
    }

    @Test
    void manualExpansion() throws Exception {
        assertEquals("body closed", run("""
                        StringBuilder log = new StringBuilder();
                        {
                            final Res res = new Res(log);
                            java.lang.Throwable primary = null;
                            try {
                                res.touch();
                            } catch (java.lang.Throwable thrown) {
                                primary = thrown;
                                throw thrown;
                            } finally {
                                if (res != null) {
                                    if (primary != null) {
                                        try {
                                            res.close();
                                        } catch (java.lang.Throwable suppressed) {
                                            primary.addSuppressed(suppressed);
                                        }
                                    } else {
                                        res.close();
                                    }
                                }
                            }
                        }
                        return log.toString();""",
                "static class Res implements AutoCloseable {"
                        + " final StringBuilder log; Res(StringBuilder log) { this.log = log; }"
                        + " void touch() { log.append(\"body\"); }"
                        + " public void close() { log.append(\" closed\"); } }"));
    }

    @Test
    void finallyPlain() throws Exception {
        assertEquals("a f", run("""
                        StringBuilder log = new StringBuilder();
                        try { log.append("a"); } finally { log.append(" f"); }
                        return log.toString();"""));
    }

    @Test
    void finallyWithRethrowingCatch() throws Exception {
        assertEquals("a f", run("""
                        StringBuilder log = new StringBuilder();
                        try { log.append("a"); } catch (Throwable t) { throw t; } finally { log.append(" f"); }
                        return log.toString();"""));
    }

    @Test
    void finallyWithIf() throws Exception {
        assertEquals("a f", run("""
                        StringBuilder log = new StringBuilder();
                        Object o = log;
                        try { log.append("a"); } finally { if (o != null) { log.append(" f"); } }
                        return log.toString();"""));
    }

    @Test
    void finallyInBlock() throws Exception {
        assertEquals("a f", run("""
                        StringBuilder log = new StringBuilder();
                        {
                            try { log.append("a"); } finally { log.append(" f"); }
                        }
                        return log.toString();"""));
    }

    @Test
    void tryWithResourcesOnExistingVariable() throws Exception {
        assertEquals("body closed", run("""
                        StringBuilder log = new StringBuilder();
                        Res res = new Res(log);
                        try (res) {
                            res.touch();
                        }
                        return log.toString();""",
                "static class Res implements AutoCloseable {\n"
                        + "    final StringBuilder log;\n"
                        + "    Res(StringBuilder log) { this.log = log; }\n"
                        + "    void touch() { log.append(\"body\"); }\n"
                        + "    public void close() { log.append(\" closed\"); }\n"
                        + "}"));
    }

    @Test
    void finallyOnReturn() throws Exception {
        assertEquals("1 f", run("""
                        StringBuilder log = new StringBuilder();
                        int v = m(log);
                        return v + " " + log;""",
                "static int m(StringBuilder log) { try { return 1; } finally { log.append(\"f\"); } }"));
    }

    @Test
    void finallyOnBreakAndContinue() throws Exception {
        assertEquals("c0 f0 c1 f1 b2 f2", run("""
                        StringBuilder log = new StringBuilder();
                        for (int i = 0; i < 5; i++) {
                            try {
                                if (i == 0 || i == 1) { log.append("c" + i + " "); continue; }
                                log.append("b" + i + " ");
                                break;
                            } finally {
                                log.append("f" + i + (i == 2 ? "" : " "));
                            }
                        }
                        return log.toString();"""));
    }

    @Test
    void finallyOnReturnInCatch() throws Exception {
        assertEquals("r ! 1", run("""
                        StringBuilder log = new StringBuilder();
                        int v = m(log);
                        return log + " " + v;""",
                "static int m(StringBuilder log) {"
                        + " try { throw new IllegalStateException(); }"
                        + " catch (IllegalStateException e) { log.append(\"r \"); return 1; }"
                        + " finally { log.append(\"!\"); } }"));
    }

    @Test
    void finallyAfterThrowInCatch() throws Exception {
        assertEquals("c f outer", run("""
                        StringBuilder log = new StringBuilder();
                        try {
                            m(log);
                        } catch (RuntimeException e) {
                            log.append("outer");
                        }
                        return log.toString();""",
                "static void m(StringBuilder log) {"
                        + " try { throw new IllegalStateException(); }"
                        + " catch (IllegalStateException e) { log.append(\"c \"); throw new RuntimeException(\"x\"); }"
                        + " finally { log.append(\"f \"); } }"));
    }

    @Test
    void finallyOnNestedJumps() throws Exception {
        assertEquals("i1 o1", run("""
                        StringBuilder log = new StringBuilder();
                        outer:
                        for (int i = 0; i < 3; i++) {
                            try {
                                try {
                                    if (i == 0) { break outer; }
                                } finally {
                                    log.append("i" + (i + 1) + " ");
                                }
                            } finally {
                                log.append("o" + (i + 1));
                            }
                        }
                        return log.toString();"""));
    }

    @Test
    void nestedTryInsideTry() throws Exception {
        assertEquals("outer:a outer:b outer:c ok", run("""
                        StringBuilder log = new StringBuilder();
                        for (int i = 0; i < 4; i++) {
                            try {
                                try {
                                    if (i == 0) { throw new IllegalStateException("a"); }
                                    if (i == 1) { throw new IllegalArgumentException("x"); }
                                } catch (IllegalArgumentException e) {
                                    if (i == 1) { throw new IllegalStateException("b"); }
                                }
                                if (i == 2) { throw new IllegalStateException("c"); }
                                log.append("ok");
                            } catch (IllegalStateException e) {
                                log.append("outer:" + e.getMessage() + " ");
                            }
                        }
                        return log.toString().trim();"""));
    }

    @Test
    void varargsConstructor() throws Exception {
        assertEquals("3 0 1", run("""
                        return new Many("a", "b", "c").count + " " + new Many().count + " " + new Many("x").count;""",
                "static class Many {\n"
                        + "    final int count;\n"
                        + "    Many(String... parts) { this.count = parts.length; }\n"
                        + "}"));
    }

    @Test
    void localClass() throws Exception {
        assertEquals("7", run("""
                        class Adder {
                            int add(int a, int b) { return a + b; }
                        }
                        return String.valueOf(new Adder().add(3, 4));"""));
    }
}
