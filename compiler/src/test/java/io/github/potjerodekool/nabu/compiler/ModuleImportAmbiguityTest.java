package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * import module: a name that two imported modules provide as different types is an error where it is used.
 */
class ModuleImportAmbiguityTest {

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

    private void assertAmbiguous(final String code,
                                 final boolean expected) throws Exception {
        final var result = compile("Sample", code, "25");
        final var ambiguous = result.errors().stream().filter(it -> it.contains("is ambiguous")).toList();
        if (expected) {
            assertEquals(1, ambiguous.size(), String.join("\n", result.errors()));
            assertTrue(ambiguous.getFirst().contains("Reference to 'Date' is ambiguous, both java.sql.Date and "
                    + "java.util.Date match"), ambiguous.getFirst());
            assertTrue(result.rc() != 0);
        } else {
            assertEquals(List.of(), ambiguous);
            assertEquals(List.of(), result.errors());
            assertEquals(0, result.rc());
        }
    }

    @Test
    void usingAnAmbiguousNameIsAnError() throws Exception {
        assertAmbiguous("""
                import module java.base;
                import module java.sql;

                public class Sample {
                    Date date;
                }
                """, true);
    }

    @Test
    void anUnusedAmbiguousNameIsFine() throws Exception {
        assertAmbiguous("""
                import module java.base;
                import module java.sql;

                public class Sample {
                    Time time = new Time(0L);
                    List<String> list = new ArrayList<>();
                }
                """, false);
    }

    @Test
    void aSingleTypeImportResolvesTheAmbiguity() throws Exception {
        assertAmbiguous("""
                import module java.base;
                import module java.sql;
                import java.util.Date;

                public class Sample {
                    Date date = new Date(0L);
                }
                """, false);
    }

    @Test
    void aTypeOfTheFileItselfResolvesTheAmbiguity() throws Exception {
        assertAmbiguous("""
                import module java.base;
                import module java.sql;

                public class Sample {
                    static class Date {}
                    Date date = new Date();
                }
                """, false);
    }
}
