package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compact source files (JEP 512): members without a class declaration.
 */
class CompactSourceFileRunTest {

    @TempDir
    Path tempDir;

    private record Result(int rc, java.util.List<String> errors, Path outDir) {
    }

    private Result compile(final String className,
                           final String code,
                           final String release) throws Exception {
        final var srcDir = tempDir.resolve("src");
        final var javaFile = srcDir.resolve(className + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, code);

        final var outDir = tempDir.resolve("out");
        final var rootPath = "C:\\projects\\nabu\\compiler";
        final var builder = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true")
                .option(CompilerOption.SYSTEM, rootPath + "\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false");
        if (release != null) {
            builder.option(CompilerOption.RELEASE, release);
        }

        final var compiler = new NabuCompiler();
        final var messages = new ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                messages.add(d.getMessage(Locale.ROOT).toString());
            }
        });
        final var rc = compiler.compile(builder.build());
        return new Result(rc, messages, outDir);
    }

    private Class<?> compileAndLoad(final String className,
                                    final String code) throws Exception {
        final var result = compile(className, code, "25");
        assertEquals(0, result.errors().size(), String.join("\n", result.errors()));
        assertEquals(0, result.rc());
        final var loader = new URLClassLoader(new java.net.URL[]{result.outDir().toUri().toURL()},
                getClass().getClassLoader());
        return loader.loadClass(className);
    }

    /** The implicit class is not public, so the launcher makes the method accessible too. */
    private static void run(final Class<?> cls,
                            final String[] args) throws Exception {
        final var main = cls.getDeclaredMethod("main", String[].class);
        main.setAccessible(true);
        main.invoke(null, (Object) args);
    }

    @Test
    void staticMainAndMembers() throws Exception {
        final var cls = compileAndLoad("Hello", """
                import java.util.List;

                int counter = 40;
                static String greeting = "hi";

                record Pair(int a, int b) {}

                static int twice(int x) {
                    return x * 2;
                }

                int next() {
                    return ++counter;
                }

                static void main(String[] args) {
                    var list = new ArrayList<String>();
                    list.add(greeting);
                    System.getProperties().put("compact.result", list.get(0) + twice(21) + new Pair(1, 2).b()
                            + new Hello().next());
                }
                """);
        run(cls, new String[0]);
        assertEquals("hi42241", System.getProperties().remove("compact.result"));
    }

    @Test
    void instanceMainWithoutArguments() throws Exception {
        final var cls = compileAndLoad("Greeter", """
                String name = "world";

                void main() {
                    System.getProperties().put("compact.instance", "hello " + name);
                }
                """);
        run(cls, new String[0]);
        assertEquals("hello world", System.getProperties().remove("compact.instance"));
    }

    @Test
    void instanceMainWithArguments() throws Exception {
        final var cls = compileAndLoad("WithArgs", """
                String prefix = "args:";

                void main(String[] args) {
                    System.getProperties().put("compact.args", prefix + args.length);
                }
                """);
        run(cls, new String[]{"a", "b"});
        assertEquals("args:2", System.getProperties().remove("compact.args"));
    }

    @Test
    void requiresJava25() throws Exception {
        final var result = compile("Old", """
                void main() {
                }
                """, "21");
        assertTrue(result.rc() != 0);
        assertTrue(result.errors().stream().anyMatch(it -> it.toLowerCase(Locale.ROOT).contains("compact source files requires java 25")),
                String.join("\n", result.errors()));
    }
}
