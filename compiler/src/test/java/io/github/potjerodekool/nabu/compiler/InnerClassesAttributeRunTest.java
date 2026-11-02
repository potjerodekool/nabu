package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nested classes need the InnerClasses attribute (JVMS 4.7.6), not only NestHost/NestMembers. Spring's classpath
 * scanner read {@code Outer$Status.class} and failed with "innerAccessFlags is null", and reflection could not
 * tell a member class from a top-level one.
 */
class InnerClassesAttributeRunTest {

    @TempDir
    Path tempDir;

    private ClassLoader compile(final String code) throws Exception {
        final var srcDir = tempDir.resolve("src");
        final var javaFile = srcDir.resolve("mini/Outer.java");
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

        return new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    void memberClassesAreKnownToReflection() throws Exception {
        final var loader = compile("""
                package mini;
                public class Outer {
                    public static class Nested {
                        public static class Deeper {
                        }
                    }
                    public class Inner {
                    }
                    public enum Status {
                        AVAILABLE, SOLD
                    }
                    public interface Api {
                    }
                }
                """);

        final var outer = loader.loadClass("mini.Outer");
        final var nested = loader.loadClass("mini.Outer$Nested");
        final var deeper = loader.loadClass("mini.Outer$Nested$Deeper");
        final var inner = loader.loadClass("mini.Outer$Inner");
        final var status = loader.loadClass("mini.Outer$Status");
        final var api = loader.loadClass("mini.Outer$Api");

        assertTrue(nested.isMemberClass());
        assertSame(outer, nested.getDeclaringClass());
        assertEquals("Nested", nested.getSimpleName());
        assertTrue(Modifier.isStatic(nested.getModifiers()));

        assertSame(nested, deeper.getDeclaringClass());
        assertEquals("Deeper", deeper.getSimpleName());

        assertSame(outer, inner.getDeclaringClass());
        assertTrue(!Modifier.isStatic(inner.getModifiers()));

        assertSame(outer, status.getDeclaringClass());
        assertTrue(Modifier.isStatic(status.getModifiers()));
        assertTrue(status.isEnum());

        assertSame(outer, api.getDeclaringClass());
        assertTrue(api.isInterface());
        assertTrue(Modifier.isStatic(api.getModifiers()));

        assertEquals(
                java.util.Set.of("Nested", "Inner", "Status", "Api"),
                Arrays.stream(outer.getDeclaredClasses()).map(Class::getSimpleName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void topLevelClassIsNoMemberClass() throws Exception {
        final var loader = compile("""
                package mini;
                public class Outer {
                    public static class Nested {
                    }
                }
                """);
        final var outer = loader.loadClass("mini.Outer");
        assertTrue(!outer.isMemberClass());
        assertEquals(null, outer.getDeclaringClass());
    }
}
