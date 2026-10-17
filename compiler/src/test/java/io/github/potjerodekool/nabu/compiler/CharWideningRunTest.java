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
class CharWideningRunTest {

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
        assertEquals(0, rc + errors.get(), String.join("\n", messages));

        final var loader = new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()},
                getClass().getClassLoader());
        return loader.loadClass("mini." + className);
    }

    @Test
    void charWidensWithoutSign() throws Exception {
        final var cls = compileAndLoad("Chars", """
                package mini;
                public class Chars {
                    static char f = '\\uffff';
                    static int id(char c) { return c; }
                    public static String run() {
                        char c = '\\uffff';
                        int i = c;
                        char[] a = new char[] {'\\u8000', 'x'};
                        a[1] = (char) 0x9001;
                        long big = 3;
                        int n = 2;
                        short sh = -1;
                        int j = sh;
                        StringBuilder sb = new StringBuilder();
                        sb.append(i).append(' ');
                        sb.append((int) c).append(' ');
                        sb.append(c + 1).append(' ');
                        sb.append(a[0] + 0).append(' ');
                        sb.append(a[1] + 0).append(' ');
                        sb.append(id('\\u9000')).append(' ');
                        sb.append(f + 0).append(' ');
                        sb.append(j).append(' ');
                        sb.append(n * big + 2147483647).append(' ');
                        sb.append(c > 0x7fff);
                        return sb.toString();
                    }
                }
                """);
        assertEquals("65535 65535 65536 32768 36865 36864 65535 -1 2147483653 true",
                cls.getMethod("run").invoke(null));
    }
}
