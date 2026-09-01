package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.incremental.IncrementalBuildState;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.processor.HibernateProcessor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled
class IncrementalCompilationIntegrationTest {

    @TempDir
    Path tempDir;

    private CompilerOptions.CompilerOptionsBuilder options(final Path sourceDir,
                                                           final Path outDir,
                                                           final Path genDir) {
        final var rootPath = resolveCompilerDirectory().toString();
        return new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, sourceDir.toString())
                .option(CompilerOption.MODULE_SOURCE_PATH, rootPath + "/src/test/resources/jmods")
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, genDir.toString())
                .option(CompilerOption.INCREMENTAL, "true")
                .option(CompilerOption.BACKEND, "ASM");
    }

    private Path resolveCompilerDirectory() {
        var current = new java.io.File(".").getAbsoluteFile();
        while (!current.isDirectory() || !"nabu".equals(current.getName())) {
            current = current.getParentFile();
        }
        return new java.io.File(current, "compiler").toPath();
    }

    private List<String> compile(final CompilerOptions options) {
        final var compiler = new NabuCompiler();
        final var diagnostics = new ArrayList<String>();
        compiler.setListener(diagnostic -> diagnostics.add(
                diagnostic.getKind() + " " + diagnostic.getMessage(java.util.Locale.ROOT)
        ));
        final var result = compiler.compile(options);
        diagnostics.add(0, "result=" + result);
        return diagnostics;
    }

    private List<String> diagnostics(final CompilerOptions options) {
        final var compiler = new NabuCompiler();
        final var diagnostics = new ArrayList<String>();
        compiler.setListener(diagnostic -> diagnostics.add(
                diagnostic.getKind() + " " + diagnostic.getMessage(java.util.Locale.ROOT)
        ));
        compiler.compile(options);
        return diagnostics;
    }

    private static FileTime hoursAgo(final long amount) {
        return FileTime.from(Instant.now().minus(amount, ChronoUnit.HOURS));
    }

    @Test
    void unchangedSourcesAreSkippedAndClassesNotRewritten(@TempDir final Path tempDir) throws Exception {
        final var sourceDir = Files.createDirectories(tempDir.resolve("src/foo"));
        final var outDir = Files.createDirectories(tempDir.resolve("out"));

        Files.writeString(sourceDir.resolve("A.java"), """
                package foo;
                public class A {
                    public static String hello() { return "hello"; }
                }
                """);

        final var options = options(sourceDir, outDir, tempDir.resolve("generated")).build();

        final var firstDiagnostics = compile(options);
        assertEquals("result=0", firstDiagnostics.get(0), "eerste compilatie moet slagen: " + firstDiagnostics);

        final var aClass = outDir.resolve("foo/A.class");
        assertTrue(Files.exists(aClass), "A.class moet gegenereerd zijn");
        final var stateFile = IncrementalBuildState.stateFile(outDir);
        assertTrue(Files.exists(stateFile), "incremental state moet geschreven zijn");

        final var oldTime = hoursAgo(2);
        Files.setLastModifiedTime(aClass, oldTime);

        final var secondDiagnostics = compile(options);
        assertEquals("result=0", secondDiagnostics.get(0), "tweede compilatie moet slagen: " + secondDiagnostics);
        assertTrue(
                secondDiagnostics.stream().anyMatch(d -> d.contains("Incremental compilation skipped")),
                "tweede compilatie moet skippen, was: " + secondDiagnostics
        );
        assertEquals(oldTime, Files.getLastModifiedTime(aClass),
                "A.class mag niet herschreven worden bij een unveranderde bron");
    }

    @Test
    void changedSourceTriggersFullRecompileOfAllSources(@TempDir final Path tempDir) throws Exception {
        final var sourceDir = Files.createDirectories(tempDir.resolve("src/foo"));
        final var outDir = Files.createDirectories(tempDir.resolve("out"));

        Files.writeString(sourceDir.resolve("A.java"), """
                package foo;
                public class A {
                    public static String hello() { return "hello"; }
                }
                """);
        Files.writeString(sourceDir.resolve("B.java"), """
                package foo;
                public class B {
                    public static String world() { return "world"; }
                }
                """);

        final var options = options(sourceDir, outDir, tempDir.resolve("generated")).build();
        assertEquals(0, compileInt(options), "eerste compilatie moet slagen");

        final var aClass = outDir.resolve("foo/A.class");
        final var bClass = outDir.resolve("foo/B.class");
        final var oldTime = hoursAgo(2);
        Files.setLastModifiedTime(aClass, oldTime);
        Files.setLastModifiedTime(bClass, oldTime);

        Files.writeString(sourceDir.resolve("A.java"), """
                package foo;
                public class A {
                    public static String hello() { return "hello world"; }
                }
                """);

        assertEquals(0, compileInt(options), "compilatie na wijziging moet slagen");

        assertTrue(Files.getLastModifiedTime(aClass).toMillis() > oldTime.toMillis(),
                "A.class moet opnieuw gegenereerd zijn");
        assertTrue(Files.getLastModifiedTime(bClass).toMillis() > oldTime.toMillis(),
                "B.class moet ook opnieuw gegenereerd zijn (volledige hercompilatie)");
    }

    @Test
    void deletedSourceRemovesStaleClassFiles(@TempDir final Path tempDir) throws Exception {
        final var sourceDir = Files.createDirectories(tempDir.resolve("src/foo"));
        final var outDir = Files.createDirectories(tempDir.resolve("out"));

        Files.writeString(sourceDir.resolve("A.java"), """
                package foo;
                public class A {}
                """);
        Files.writeString(sourceDir.resolve("B.java"), """
                package foo;
                public class B {}
                """);

        final var options = options(sourceDir, outDir, tempDir.resolve("generated")).build();
        assertEquals(0, compileInt(options), "eerste compilatie moet slagen");

        assertTrue(Files.exists(outDir.resolve("foo/B.class")));
        Files.delete(sourceDir.resolve("B.java"));

        assertEquals(0, compileInt(options), "compilatie na verwijdering moet slagen");

        assertFalse(Files.exists(outDir.resolve("foo/B.class")), "B.class moet opgeruimd zijn");
        assertTrue(Files.exists(outDir.resolve("foo/A.class")), "A.class moet blijven bestaan");
    }

    @Test
    void generatedSourcesMakeProcessorRunsSkippableAndOptionChangesInvalidate(@TempDir final Path tempDir) throws Exception {
        final var sourceDir = Files.createDirectories(tempDir.resolve("src/io/github/potjerodekool/test"));
        final var outDir = Files.createDirectories(tempDir.resolve("out"));
        final var genDir = Files.createDirectories(tempDir.resolve("generated"));

        Files.writeString(sourceDir.resolve("Person.java"), """
                package io.github.potjerodekool.test;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                @Entity
                public class Person {
                    @Id
                    private Long id;
                }
                """);

        final var options = options(sourceDir, outDir, genDir)
                .option(CompilerOption.CLASS_PATH, locationOf(Entity.class))
                .option(CompilerOption.ANNOTATION_PROCESSOR_PATH, locationOf(HibernateProcessor.class))
                .build();

        assertEquals(0, compileInt(options), "eerste compilatie met processor moet slagen");
        assertTrue(Files.exists(genDir.resolve("io/github/potjerodekool/test/Person_.java")),
                "Person_.java gegenereerd");
        assertTrue(Files.exists(outDir.resolve("io/github/potjerodekool/test/Person_.class")),
                "Person_.class gecompileerd");

        final var secondDiagnostics = diagnostics(options);
        assertTrue(
                secondDiagnostics.stream().anyMatch(d -> d.contains("Incremental compilation skipped")),
                "hercompilatie met dezelfde opties moet skippen (geen processor-run): " + secondDiagnostics
        );
        assertTrue(Files.exists(outDir.resolve("io/github/potjerodekool/test/Person_.class")),
                "Person_.class moet intact blijven na skip");

        final var changedOptions = options(sourceDir, outDir, genDir)
                .option(CompilerOption.CLASS_PATH, locationOf(Entity.class))
                .option(CompilerOption.ANNOTATION_PROCESSOR_PATH, locationOf(HibernateProcessor.class))
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.TARGET_VERSION, "1.8")
                .build();

        final var thirdDiagnostics = diagnostics(changedOptions);
        assertFalse(
                thirdDiagnostics.stream().anyMatch(d -> d.contains("Incremental compilation skipped")),
                "veranderende opties (target version) mogen niet skippen: " + thirdDiagnostics
        );
        assertEquals(0, compileInt(changedOptions), "compilatie met gewijzigde opties moet slagen");
    }

    @Test
    void generatedOutputBecomesStaleAndIsRemovedWhenAnnotationDisappears(@TempDir final Path tempDir) throws Exception {
        final var sourceDir = Files.createDirectories(tempDir.resolve("src/io/github/potjerodekool/test"));
        final var outDir = Files.createDirectories(tempDir.resolve("out"));
        final var genDir = Files.createDirectories(tempDir.resolve("generated"));

        Files.writeString(sourceDir.resolve("Person.java"), """
                package io.github.potjerodekool.test;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                @Entity
                public class Person {
                    @Id
                    private Long id;
                }
                """);

        final var options = options(sourceDir, outDir, genDir)
                .option(CompilerOption.CLASS_PATH, locationOf(Entity.class))
                .option(CompilerOption.ANNOTATION_PROCESSOR_PATH, locationOf(HibernateProcessor.class))
                .build();

        assertEquals(0, compileInt(options), "eerste compilatie met processor moet slagen");
        assertTrue(Files.exists(outDir.resolve("io/github/potjerodekool/test/Person_.class")),
                "Person_.class gegenereerd");

        Files.writeString(sourceDir.resolve("Person.java"), """
                package io.github.potjerodekool.test;
                public class Person {
                    private Long id;
                }
                """);

        assertEquals(0, compileInt(options), "compilatie na verwijderen van @Entity moet slagen");

        assertFalse(Files.exists(outDir.resolve("io/github/potjerodekool/test/Person_.class")),
                "Person_.class moet als stale worden opgeruimd");
        assertTrue(Files.exists(outDir.resolve("io/github/potjerodekool/test/Person.class")),
                "Person.class moet blijven bestaan");
    }

    @Test
    void missingStateFileTriggersFullCompilation(@TempDir final Path tempDir) throws Exception {
        final var sourceDir = Files.createDirectories(tempDir.resolve("src/foo"));
        final var outDir = Files.createDirectories(tempDir.resolve("out"));

        Files.writeString(sourceDir.resolve("A.java"), """
                package foo;
                public class A {}
                """);

        final var options = options(sourceDir, outDir, tempDir.resolve("generated")).build();

        final var diagnostics = diagnostics(options);
        assertFalse(
                diagnostics.stream().anyMatch(d -> d.contains("Incremental compilation skipped")),
                "zonder state mag niet geskipped worden. diagnostics: " + diagnostics
        );
        assertTrue(Files.exists(outDir.resolve("foo/A.class")),
                "zonder state moet volledig gecompileerd worden: " + diagnostics);
    }

    private int compileInt(final CompilerOptions options) {
        final var compiler = new NabuCompiler();
        final var result = compiler.compile(options);
        assertNotNull(result, "compile must not return null");
        return result;
    }

    private static String locationOf(final Class<?> clazz) {
        try {
            return Path.of(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}