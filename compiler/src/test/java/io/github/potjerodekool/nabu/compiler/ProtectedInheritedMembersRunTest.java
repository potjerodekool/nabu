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
 * Spring AOT generates subclasses of library classes in another package that call a protected constructor
 * (<code>super(selector, context)</code>, context a nested class) and a protected generic method
 * (<code>convertOne(Object, boolean, Class&lt;T&gt;)</code>). Both were reported as "Failed to resolve method".
 */
class ProtectedInheritedMembersRunTest {

    @TempDir
    Path tempDir;

    private Class<?> compileAndLoad(final String main, final java.util.Map<String, String> sources) throws Exception {
        final var srcDir = tempDir.resolve("src");
        for (final var entry : sources.entrySet()) {
            final var file = srcDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
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
        final var text = String.join(System.lineSeparator(), messages);
        assertEquals(0, errors.get(), text);
        assertEquals(0, rc, text);

        final var loader = new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()},
                getClass().getClassLoader());
        return loader.loadClass(main);
    }

    @Test
    void protectedConstructorAndGenericMethodOfSuperclassInOtherPackage() throws Exception {
        final var cls = compileAndLoad("mini.sub.Impl", java.util.Map.of(
                "mini/base/Support.java", """
                        package mini.base;
                        public class Support {
                            public static class Context { }
                            private final String selector;
                            protected Support(String selector, Context context) {
                                this.selector = selector;
                            }
                            protected <T> T convertOne(Object value, boolean flag, Class<T> type) {
                                return type.cast(value);
                            }
                        }
                        """,
                "mini/sub/Impl.java", """
                        package mini.sub;
                        import mini.base.Support;
                        public class Impl extends Support {
                            public Impl(Support.Context context) {
                                super("sel", context);
                            }
                            public static String run() {
                                Impl impl = new Impl(new Support.Context());
                                return impl.convertOne("value", false, String.class);
                            }
                        }
                        """));
        assertEquals("value", cls.getMethod("run").invoke(null));
    }

    @Test
    void javaSqlTypesAreVisible() throws Exception {
        final var cls = compileAndLoad("mini.Sql", java.util.Map.of(
                "mini/Sql.java", """
                        package mini;
                        import javax.sql.DataSource;
                        public class Sql {
                            public static String run(DataSource dataSource) {
                                return dataSource == null ? "null" : "set";
                            }
                        }
                        """));
        assertEquals("null", cls.getMethod("run", Class.forName("javax.sql.DataSource")).invoke(null, new Object[]{null}));
    }
}
