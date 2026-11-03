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
 * `@X(v)` is `@X(value = v)`. The Nabu frontend passed the bare value on: a literal was dropped silently and a class
 * literal crashed TypeEnter with a ClassCastException (found with `@ExceptionHandler(ApiException.class)`).
 */
class NabuSingleElementAnnotationRunTest {

    @TempDir
    Path tempDir;

    private Object run(final String source, final String method) throws Exception {
        final var srcDir = tempDir.resolve("src");
        final var file = srcDir.resolve("mini/Single.nabu");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);

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

        final var loader = new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()}, getClass().getClassLoader());
        final var cls = loader.loadClass("mini.Single");
        return cls.getMethod(method).invoke(cls.getDeclaredConstructor().newInstance());
    }

    @Test
    void annotationArrayValueIndexedThroughLocal() throws Exception {
        final var result = run("""
                package mini;

                import java.lang.annotation.Target;
                import java.lang.annotation.ElementType;

                @Target(ElementType.TYPE)
                public class Single {
                    public fun text(): String {
                        final var names = Single.class.getAnnotation(Target.class).value();
                        return names[0].name();
                    }
                }
                """, "text");
        assertEquals("TYPE", result);
    }

    @Test
    void annotationArrayValueIndexedInChain() throws Exception {
        final var result = run("""
                package mini;

                import java.lang.annotation.Target;
                import java.lang.annotation.ElementType;

                @Target(ElementType.TYPE)
                public class Single {
                    public fun text(): String {
                        return Single.class.getAnnotation(Target.class).value()[0].name();
                    }
                }
                """, "text");
        assertEquals("TYPE", result);
    }

    @Test
    void singleElementAnnotationValuesAreKept()throws Exception {
        final var result = run("""
                package mini;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.CLASS)
                public class Single {
                    public fun text(): String {
                        return Single.class.getAnnotation(Retention.class).value().name();
                    }
                }
                """, "text");
        assertEquals("CLASS", result);
    }
}
