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
 * De ASM-backend initialiseert aan het begin van elk blok de temp-slots die
 * niet live-in zijn. Een object dat alleen via een veldtoegang
 * ({@code Values(obj, veld)}) gebruikt werd, telde niet als gelezen en werd
 * zo gewist (picocli's GroupMatchContainer.trim: NullPointerException).
 */
class FieldAccessLivenessRunTest {

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
    void objectUsedOnlyThroughFieldSurvivesBlocks() throws Exception {
        final var cls = compileAndLoad("Live", """
                package mini;
                import java.util.ArrayList;
                import java.util.Iterator;
                import java.util.List;
                public class Live {
                    static class Box { int v; List<String> tags = new ArrayList<String>(); }
                    public static int run() {
                        List<Box> list = new ArrayList<Box>();
                        Box first = new Box();
                        first.v = 3;
                        first.tags.add("x");
                        list.add(first);
                        list.add(new Box());
                        int sum = 0;
                        for (Iterator<Box> it = list.iterator(); it.hasNext(); ) {
                            Box b = it.next();
                            if (b.v == 0) { it.remove(); }
                            sum += b.v + b.tags.size();
                        }
                        return sum * 10 + list.size();
                    }
                }
                """);
        assertEquals(41, cls.getMethod("run").invoke(null));
    }
}
