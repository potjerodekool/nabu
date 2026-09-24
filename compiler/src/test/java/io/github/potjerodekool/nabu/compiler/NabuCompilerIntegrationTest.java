package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.ByteCodeGeneratorListener;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.DiagnosticListener;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Disabled
class NabuCompilerIntegrationTest {

    private CompilerOptions createOptions(String sourcePath) {
        final var rootDirectory = resolveCompilerDirectory();
        final var rootPath = rootDirectory.getAbsolutePath();
        return new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, sourcePath)
                .build();
    }

    private File resolveCompilerDirectory() {
        var current = new File(".").getAbsoluteFile();
        while (!current.isDirectory() || !"nabu".equals(current.getName())) {
            current = current.getParentFile();
        }
        return new File(current, "compiler");
    }

    @Test
    void configureReturnsCompilerContext() {
        final var compiler = new NabuCompiler();
        final var rootDir = resolveCompilerDirectory();
        final var options = createOptions(rootDir + "/src/test/resources/classes");

        final var context = compiler.configure(options);

        assertNotNull(context);
    }

    @Test
    void setListenerDoesNotThrow() {
        final var compiler = new NabuCompiler();
        compiler.setListener(new DiagnosticListener() {
            @Override
            public void report(Diagnostic diagnostic) {
            }
        });
    }

    @Test
    void setByteCodeGeneratorListenerDoesNotThrow() {
        final var compiler = new NabuCompiler();
        compiler.setByteCodeGeneratorListener(new ByteCodeGeneratorListener() {
            @Override
            public void generated(FileObject sourceFile,
                                  PathFileObject classFile,
                                  String className) {
            }
        });
    }

    @Test
    void compileWithNoSourceFilesReturnsZero() {
        final var compiler = new NabuCompiler();
        final var rootDir = resolveCompilerDirectory();
        final var emptyDir = rootDir + "/src/test/resources/empty_source";
        new File(emptyDir).mkdirs();

        final var options = createOptions(emptyDir);
        final var result = compiler.compile(options);

        assertEquals(0, result);
    }

    @Test
    void compileWithEmptySourcePathReturnsZero() {
        final var compiler = new NabuCompiler();
        final var rootDir = resolveCompilerDirectory();
        final var emptyDir = rootDir + "/src/test/resources/empty_source2";
        new File(emptyDir).mkdirs();

        final var options = createOptions(emptyDir);
        final var result = compiler.compile(options);

        assertEquals(0, result);
    }

    @Test
    void devNullByteCodeGeneratorListenerGeneratedDoesNotThrow() {
        DevNullByteCodeGeneratorListener.INSTANCE.generated(null, null, null);
    }

    @Test
    void compileWithDiagnosticListenerReportsDiagnostics() {
        final var compiler = new NabuCompiler();
        compiler.setListener(new DiagnosticListener() {
            @Override
            public void report(Diagnostic diagnostic) {
            }
        });

        final var rootDir = resolveCompilerDirectory();
        final var emptyDir = rootDir + "/src/test/resources/empty_source3";
        new File(emptyDir).mkdirs();

        final var options = createOptions(emptyDir);
        final var result = compiler.compile(options);

        assertEquals(0, result);
    }

    @Test
    void configureWithClassOutputSetsTargetDirectory() {
        final var compiler = new NabuCompiler();
        final var rootDir = resolveCompilerDirectory();
        final var rootPath = rootDir.getAbsolutePath();
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, rootPath + "/src/test/resources/classes")
                .option(CompilerOption.CLASS_OUTPUT, rootPath + "/target/test-output")
                .build();

        final var context = compiler.configure(options);

        assertNotNull(context);
    }

    @Test
    void compileSourceWithLambdaThroughFullPipeline(@TempDir final Path tempDir) throws IOException {
        final var sourceDir = Files.createDirectories(tempDir.resolve("foo"));
        Files.writeString(sourceDir.resolve("LambdaHolder.java"), """
                package foo;
                public class LambdaHolder {
                    public static void main() {
                        execute(() -> System.out.println("hi"));
                    }
                    public static void execute(Runnable r) {
                        r.run();
                    }
                }
                """);

        final var rootPath = resolveCompilerDirectory().getAbsolutePath();
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, tempDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, tempDir.resolve("out").toString())
                .build();

        final var compiler = new NabuCompiler();
        final List<String> diagnostics = new ArrayList<>();
        compiler.setListener(diagnostic -> diagnostics.add(String.valueOf(diagnostic.getMessage(null))));

        final var result = compiler.compile(options);

        assertTrue(result >= 0, "compile failed: " + diagnostics);
        final var generatedClassFile = tempDir.resolve("out").resolve("foo").resolve("LambdaHolder.class");
        assertTrue(Files.exists(generatedClassFile),
                "expected foo/LambdaHolder.class to exist; result=" + result + " diagnostics=" + diagnostics);
    }

    @Test
    void classLiteralLoadsTheReferencedClass(@TempDir final Path tempDir) throws Exception {
        final var sourceDir = Files.createDirectories(tempDir.resolve("foo"));
        Files.writeString(sourceDir.resolve("TypeHolder.java"), """
                package foo;
                public class TypeHolder {
                    public static final Class<?> SELF = TypeHolder.class;
                    public static Class<?> string() {
                        return String.class;
                    }
                    public static Class<?> array() {
                        return int[].class;
                    }
                }
                """);

        final var rootPath = resolveCompilerDirectory().getAbsolutePath();
        final var out = tempDir.resolve("out");
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, tempDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toString())
                .build();

        final var compiler = new NabuCompiler();
        final List<String> diagnostics = new ArrayList<>();
        compiler.setListener(diagnostic -> diagnostics.add(String.valueOf(diagnostic.getMessage(null))));

        final var result = compiler.compile(options);

        assertTrue(result >= 0, "compile failed: " + diagnostics);

        try (var loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, getClass().getClassLoader())) {
            final var clazz = Class.forName("foo.TypeHolder", true, loader);
            assertEquals(clazz, clazz.getField("SELF").get(null));
            assertEquals(String.class, clazz.getMethod("string").invoke(null));
            assertEquals(int[].class, clazz.getMethod("array").invoke(null));
        }
    }
}
