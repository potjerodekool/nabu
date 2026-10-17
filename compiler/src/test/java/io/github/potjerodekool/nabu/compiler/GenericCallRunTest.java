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
 * Generieke aanroepen en overloads via de ASM-backend (picocli op de JVM):
 * <ul>
 *   <li>{@code <T> T[] toArray(T[])}: T afgeleid uit het argument, met checkcast;</li>
 *   <li>self-type builders ({@code Builder<T extends Builder<T>>}), ook met
 *       gelijknamige geneste klassen: de superklasse hield zijn typeargumenten
 *       niet, en de subtypecontrole liep oneindig door;</li>
 *   <li>{@code new BigDecimal(String)} koos de constructor met {@code char[]}.</li>
 * </ul>
 */
class GenericCallRunTest {

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
    void selfTypedBuilder() throws Exception {
        final var cls = compileAndLoad("Bld", """
                package mini;
                public class Bld {
                    static abstract class Base<T extends Base<T>> {
                        String label;
                        T label(String value) { label = value; return self(); }
                        abstract T self();
                    }
                    static class Pos extends Base<Pos> {
                        Pos self() { return this; }
                        String build() { return "pos:" + label; }
                    }
                    public static String run() {
                        return new Pos().label("x").build();
                    }
                }
                """);
        assertEquals("pos:x", cls.getMethod("run").invoke(null));
    }

    @Test
    void sameNamedBuilders() throws Exception {
        final var cls = compileAndLoad("Spec", """
                package mini;
                public class Spec {
                    static abstract class Arg {
                        abstract static class Builder<T extends Builder<T>> {
                            String label;
                            T label(String value) { label = value; return self(); }
                            abstract T self();
                        }
                    }
                    static class Pos extends Arg {
                        static Builder builder() { return new Builder(); }
                        static class Builder extends Arg.Builder<Builder> {
                            Builder self() { return this; }
                            String build() { return "pos:" + label; }
                        }
                    }
                    public static String run() {
                        return Pos.builder().label("x").build();
                    }
                }
                """);
        assertEquals("pos:x", cls.getMethod("run").invoke(null));
    }

    @Test
    void bigDecimalFromString() throws Exception {
        final var cls = compileAndLoad("Bd", """
                package mini;
                import java.math.BigDecimal;
                public class Bd {
                    public static String run(String value) {
                        return new BigDecimal(value).toString();
                    }
                }
                """);
        assertEquals("1.5", cls.getMethod("run", String.class).invoke(null, "1.5"));
    }

    @Test
    void genericArray() throws Exception {
        final var cls = compileAndLoad("Gen", """
                package mini;
                import java.util.ArrayList;
                import java.util.List;
                public class Gen {
                    public static String[] run() {
                        List<String> list = new ArrayList<String>();
                        list.add("a");
                        return list.toArray(new String[0]);
                    }
                }
                """);
        assertEquals(1, ((String[]) cls.getMethod("run").invoke(null)).length);
    }
}
