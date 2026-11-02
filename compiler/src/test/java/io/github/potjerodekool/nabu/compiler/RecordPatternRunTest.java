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
 * Record patterns in instanceof, lowered to a type check and assignments of the bindings.
 */
class RecordPatternRunTest {

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
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SYSTEM, rootPath + "\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .option(CompilerOption.RELEASE, "21")
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
    void recordPatternInIf() throws Exception {
        final var cls = compileAndLoad("Rp", """
                package mini;
                public class Rp {
                    record Point(int x, int y) {}
                    record Line(Point from, Point to) {}
                    record Named(String name, Object value) {}
                    static int sum(Object o) {
                        if (o instanceof Point(int x, int y)) {
                            return x + y;
                        }
                        return -1;
                    }
                    static int twice(Object o, Object p) {
                        int r = 0;
                        if (o instanceof Point(int x, int y)) { r += x * y; }
                        if (p instanceof Point(int x, int y)) { r += x - y; }
                        return r;
                    }
                    static int length(Object o) {
                        if (o instanceof Line(Point(int a, int b), Point(int c, int d))) {
                            return (c - a) + (d - b);
                        }
                        return -1;
                    }
                    static String name(Object o) {
                        if (o instanceof Named(String n, Object v) && n.length() > 1) {
                            return n + "=" + v;
                        } else {
                            return "none";
                        }
                    }
                    public static String run() {
                        return sum(new Point(3, 4)) + " " + sum("x") + " " + twice(new Point(2, 5), new Point(9, 4)) + " "
                                + length(new Line(new Point(1, 1), new Point(4, 5))) + " "
                                + name(new Named("ab", 7)) + " " + name(new Named("a", 7)) + " " + name(null);
                    }
                }
                """);
        assertEquals("7 -1 15 7 ab=7 none none", cls.getMethod("run").invoke(null));
    }
}
