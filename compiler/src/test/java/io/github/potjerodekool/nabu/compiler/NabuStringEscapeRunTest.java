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
 * The Nabu frontend left the escape sequences of string and char literals as written: "a\nb" was the four
 * characters a, backslash, n, b. Found when the petstore upload message showed a literal backslash-n in JSON.
 */
class NabuStringEscapeRunTest {

    @TempDir
    Path tempDir;

    private Object run(final String source, final String method) throws Exception {
        final var srcDir = tempDir.resolve("src");
        final var file = srcDir.resolve("mini/Escapes.nabu");
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
        final var cls = loader.loadClass("mini.Escapes");
        return cls.getMethod(method).invoke(cls.getDeclaredConstructor().newInstance());
    }

    @Test
    void stringLiteralEscapesAreProcessed() throws Exception {
        final var result = run("""
                package mini;

                public class Escapes {
                    public fun text(): String {
                        return "a\\nb\\t\\"q\\"\\\\ \\u0041";
                    }
                }
                """, "text");
        assertEquals("a\nb\t\"q\"\\ A", result);
    }

    @Test
    void charLiteralEscapesAreProcessed() throws Exception {
        final var result = run("""
                package mini;

                public class Escapes {
                    public fun newline(): char {
                        return '\\n';
                    }
                }
                """, "newline");
        assertEquals('\n', result);
    }

    @Test
    void plainStringIsUnchanged() throws Exception {
        final var result = run("""
                package mini;

                public class Escapes {
                    public fun text(): String {
                        return "plain text";
                    }
                }
                """, "text");
        assertEquals("plain text", result);
    }
}
