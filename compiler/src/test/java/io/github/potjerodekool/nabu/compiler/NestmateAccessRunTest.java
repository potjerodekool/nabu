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
 * char via de ASM-backend: char is in de IR een i16 met {@code unsigned}, zodat
 * de backend {@code C}-descriptors, {@code CALOAD}/{@code CASTORE} en
 * {@code I2C} kiest in plaats van de short-varianten. Daarnaast typeert de
 * resolver {@code char + int} als int (JLS 5.6.2), en {@code int * long} als long.
 */
class NestmateAccessRunTest {

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
    void privateAccessBetweenNestmates() throws Exception {
        final var cls = compileAndLoad("Outer", """
                package mini;
                public class Outer {
                    private int secret = 5;
                    private static String tag() { return "t"; }
                    static class Nested {
                        private int n = 2;
                        private int twice() { return n * 2; }
                        int peek(Outer o) { return o.secret; }
                    }
                    class Inner {
                        private int inner() { return secret + 1; }
                    }
                    public static String run() {
                        Outer o = new Outer();
                        Nested nested = new Nested();
                        Inner in = o.new Inner();
                        Runnable r = new Runnable() {
                            public void run() { o.secret = o.secret + 10; }
                        };
                        r.run();
                        java.util.function.IntSupplier s = () -> o.secret + nested.n;
                        return nested.twice() + " " + nested.peek(o) + " " + in.inner() + " " + tag() + " " + s.getAsInt();
                    }
                }
                """);
        assertEquals("4 15 16 t 17", cls.getMethod("run").invoke(null));
    }
}
