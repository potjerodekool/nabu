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
 * Lambda's buiten een methode-argument via de ASM-backend. Een lambda in een
 * variabele-initializer werd niet omgezet (LambdaToMethod bezocht de
 * initializer niet), kreeg geen SAM-conversie en was null; een expressie-body
 * miste zijn return; gecapturede locals werden uit een verkeerd slot geladen;
 * en een functionele interface werd alleen herkend aan een annotatie die pas
 * na het completeren zichtbaar is (JLS 9.8).
 */
class LambdaTargetTypeRunTest {

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
    void lambdasOutsideMethodArguments() throws Exception {
        final var cls = compileAndLoad("Lam", """
                package mini;
                import java.util.Comparator;
                import java.util.function.IntSupplier;
                import java.util.function.UnaryOperator;
                public class Lam {
                    int f = 7;
                    static IntSupplier field = () -> 3;
                    static IntSupplier make(int base) {
                        return () -> base * 2;
                    }
                    public static String run() {
                        IntSupplier constant = () -> 1;
                        int x = 4;
                        IntSupplier captured = () -> x + 1;
                        Lam o = new Lam();
                        IntSupplier both = () -> o.f + x;
                        IntSupplier block = () -> { int y = x * 10; return y; };
                        Comparator<String> byLength = (a, b) -> a.length() - b.length();
                        UnaryOperator<String> upper = s -> s.toUpperCase();
                        boolean longer = byLength.compare("aa", "b") > 0;
                        return constant.getAsInt() + " " + captured.getAsInt() + " " + both.getAsInt()
                                + " " + block.getAsInt() + " " + field.getAsInt() + " " + make(6).getAsInt()
                                + " " + longer + " " + upper.apply("ok");
                    }
                }
                """);
        assertEquals("1 5 11 40 3 12 true OK", cls.getMethod("run").invoke(null));
    }
}
