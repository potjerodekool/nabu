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
 * A method signature refers to a type variable of its class (<code>S parallel()</code> in
 * <code>BaseStream&lt;T, S extends BaseStream&lt;T, S&gt;&gt;</code>) without declaring it. That reference became
 * a variable bounded by Object, so the call descriptor returned Object and failed with NoSuchMethodError.
 * Found by the petstore MockMvc tests (<code>AbstractMockHttpServletRequestBuilder.contentType</code>).
 */
class ClassTypeVariableDescriptorRunTest {

    @TempDir
    Path tempDir;

    private Object run(final String source, final String method) throws Exception {
        final var srcDir = tempDir.resolve("src");
        final var file = srcDir.resolve("mini/Self.nabu");
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
        final var cls = loader.loadClass("mini.Self");
        return cls.getMethod(method).invoke(cls.getDeclaredConstructor().newInstance());
    }

    @Test
    void callOnSelfBoundedClassTypeVariableUsesErasedBound() throws Exception {
        final var result = run("""
                package mini;

                import java.util.stream.BaseStream;
                import java.util.stream.Stream;

                public class Self {
                    public fun count(): long {
                        final var base : BaseStream<String, Stream<String>> = Stream.of("a", "b", "c");
                        return base.parallel().count();
                    }
                }
                """, "count");
        assertEquals(3L, result);
    }
}
