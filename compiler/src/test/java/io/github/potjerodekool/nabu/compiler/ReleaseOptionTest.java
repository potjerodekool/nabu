package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code --release}: the language level and the class file version. A language construct of a newer Java version
 * is an error.
 */
class ReleaseOptionTest {

    @TempDir
    Path tempDir;

    private record Result(int rc, List<String> errors) {
    }

    private Path compilerDirectory() {
        var current = new java.io.File(".").getAbsoluteFile();
        while (!current.isDirectory() || !"nabu".equals(current.getName())) {
            current = current.getParentFile();
        }
        return new java.io.File(current, "compiler").toPath();
    }

    private Result compile(final String release,
                           final String target,
                           final String code) throws IOException {
        final var srcDir = tempDir.resolve("src");
        final var javaFile = srcDir.resolve("mini/Sample.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, code);

        final var builder = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, compilerDirectory() + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, tempDir.resolve("out").toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .option(CompilerOption.COMPILE_LANGUAGE_SUPPORT, "true");
        if (release != null) {
            builder.option(CompilerOption.RELEASE, release);
        }
        if (target != null) {
            builder.option(CompilerOption.TARGET_VERSION, target);
        }

        final var compiler = new NabuCompiler();
        final var errors = new ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                errors.add(d.getMessage(Locale.ROOT).toString());
            }
        });
        return new Result(compiler.compile(builder.build()), errors);
    }

    private static final String SIMPLE = """
            package mini;
            public class Sample {
                int one() { return 1; }
            }
            """;

    private static final String PATTERN_SWITCH = """
            package mini;
            public class Sample {
                static String describe(Object o) {
                    return switch (o) {
                        case Integer i -> "int";
                        case String s -> "string";
                        default -> "other";
                    };
                }
            }
            """;

    private static final String CASE_NULL = """
            package mini;
            public class Sample {
                static int pick(String s) {
                    switch (s) {
                        case null: return 0;
                        default: return 1;
                    }
                }
            }
            """;

    private static final String RECORD_PATTERN = """
            package mini;
            public class Sample {
                record Point(int x, int y) {}
                static int sum(Object o) {
                    if (o instanceof Point(int x, int y)) {
                        return x + y;
                    }
                    return 0;
                }
            }
            """;

    private static String errors(final Result result) {
        return String.join("\n", result.errors());
    }

    @Test
    void withoutReleaseThereIsNoLanguageCheck() throws IOException {
        final var result = compile(null, null, PATTERN_SWITCH);
        assertFalse(errors(result).contains("requires Java"), errors(result));
    }

    @Test
    void unsupportedReleaseIsAnError() throws IOException {
        for (final var release : List.of("abc", "9", "16", "26", "")) {
            final var result = compile(release, null, SIMPLE);
            assertNotEquals(0, result.rc(), "release '" + release + "'");
            assertTrue(errors(result).contains("Unsupported value '" + release + "' for --release"),
                    "release '" + release + "': " + errors(result));
        }
    }

    @Test
    void releaseAndTargetCannotBeCombined() throws IOException {
        final var result = compile("21", "21", SIMPLE);
        assertNotEquals(0, result.rc());
        assertTrue(errors(result).contains("--release cannot be combined with --target"), errors(result));
    }

    @Test
    void patternSwitchNeedsJava21() throws IOException {
        final var result = compile("17", null, PATTERN_SWITCH);
        assertNotEquals(0, result.rc());
        assertTrue(errors(result).contains("Pattern matching for switch requires Java 21, but the release is 17"),
                errors(result));
    }

    @Test
    void patternSwitchIsAllowedFromJava21() throws IOException {
        for (final var release : List.of("21", "25")) {
            final var result = compile(release, null, PATTERN_SWITCH);
            assertFalse(errors(result).contains("requires Java"), release + ": " + errors(result));
        }
    }

    @Test
    void caseNullNeedsJava21() throws IOException {
        final var result = compile("17", null, CASE_NULL);
        assertTrue(errors(result).contains("'case null' requires Java 21"), errors(result));
    }

    @Test
    void recordPatternNeedsJava21() throws IOException {
        final var result = compile("17", null, RECORD_PATTERN);
        assertTrue(errors(result).contains("Record patterns requires Java 21"), errors(result));
        assertFalse(errors(compile("21", null, RECORD_PATTERN)).contains("requires Java"));
    }

    @Test
    void unnamedVariableNeedsJava22() throws IOException {
        final var code = """
                package mini;
                public class Sample {
                    int f(Object o) {
                        try { return 1; } catch (RuntimeException _) { return 2; }
                    }
                }
                """;
        assertTrue(errors(compile("21", null, code)).contains("requires Java 22"), "21");
        assertFalse(errors(compile("22", null, code)).contains("requires Java"), "22");
    }

    @Test
    void statementsBeforeSuperNeedJava25() throws IOException {
        final var code = """
                package mini;
                public class Sample {
                    static class Base { Base(int v) {} }
                    static class Sub extends Base {
                        Sub(int x) {
                            int y = x + 1;
                            super(y);
                        }
                    }
                }
                """;
        assertTrue(errors(compile("24", null, code)).contains("requires Java 25"), "24");
        assertFalse(errors(compile("25", null, code)).contains("requires Java"), "25");
    }

    @Test
    void moduleImportNeedsJava25AndIsNotSupportedYet() throws IOException {
        final var code = """
                package mini;
                import module java.base;
                public class Sample {
                }
                """;
        assertTrue(errors(compile("24", null, code)).contains("Module import declarations requires Java 25"), "24");
        assertTrue(errors(compile("25", null, code)).contains("not supported yet"), "25");
    }

    @Test
    void releaseSetsTheClassFileVersion() throws IOException {
        final var result = compile("21", null, SIMPLE);
        assertEquals(0, result.rc(), errors(result));
        final var bytes = Files.readAllBytes(tempDir.resolve("out/mini/Sample.class"));
        final int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        assertEquals(JavaVersion.V21.getValue(), major);
    }

    @Test
    void releaseParsing() {
        assertEquals(JavaVersion.V25, JavaVersion.parseStrict("25"));
        assertEquals(69, JavaVersion.V25.getValue());
        assertTrue(JavaVersion.V21.isAtLeast(JavaVersion.V17));
        assertFalse(JavaVersion.V17.isAtLeast(JavaVersion.V21));
        assertThrows(IllegalArgumentException.class, () -> JavaVersion.parseStrict("abc"));
    }
}
