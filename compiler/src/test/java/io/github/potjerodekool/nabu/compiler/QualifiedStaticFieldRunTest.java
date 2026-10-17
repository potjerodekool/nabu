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
 * Volledig gekwalificeerde klassenamen in expressies (JLS 6.5.2): de wortel
 * {@code java} is geen variabele of type, en de resolver meldde elke naam in
 * {@code java.text.BreakIterator.DONE} als onbekend symbool. Daarnaast een
 * statische interfacemethode ({@code Map.Entry.comparingByKey()},
 * {@code List.of}): de ASM-backend moet dan een InterfaceMethodref gebruiken.
 */
class QualifiedStaticFieldRunTest {

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
    void fullyQualifiedStaticField() throws Exception {
        final var cls = compileAndLoad("Fq", """
                package mini;
                public class Fq {
                    static int helper() { return 3; }
                    public static String run() {
                        int done = java.text.BreakIterator.DONE;
                        String qualified = done + " " + java.lang.Integer.MAX_VALUE
                                + " " + java.util.Locale.ROOT.toString().length()
                                + " " + java.lang.Math.max(4, 9)
                                + " " + (java.util.Map.Entry.comparingByKey() != null)
                                + " " + mini.Fq.helper()
                                + " " + java.util.List.of("a", "b").size();
                        return qualified + " " + shadow();
                    }
                    static int shadow() {
                        String java = "shadow";
                        return java.length();
                    }
                }
                """);
        assertEquals("-1 2147483647 0 9 true 3 2 6", cls.getMethod("run").invoke(null));
    }
}
