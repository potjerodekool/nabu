package io.github.potjerodekool.nabu.compiler.annotation.processing;

import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.log.LoggerFactory;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.util.Elements;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavacFilerTest extends AbstractCompilerTest {

    static {
        Logger logger = new Logger() {
            @Override
            public void log(final LogLevel level, final String message, final Throwable exception) {
                System.out.println(message);
            }
        };

        LoggerFactory.setProvider((name) -> {
            return logger;
        });
    }

    private final SymbolTable symbolTable = getCompilerContext().getSymbolTable();
    private final Elements elements = getCompilerContext().getElements();

    private Path tempDir;

    private final JavacFiler filer = new JavacFiler(
            symbolTable,
            elements,
            getCompilerContext().getFileManager()
    );

    private Path getTempDir() throws IOException {
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("test");
        }
        return tempDir;
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walkFileTree(getTempDir(), new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    protected void configureOptions(final CompilerOptions.CompilerOptionsBuilder optionsBuilder) {
        try {
            optionsBuilder.option(CompilerOption.SOURCE_OUTPUT, getTempDir().toAbsolutePath().toString());
            optionsBuilder.option(CompilerOption.CLASS_OUTPUT, getTempDir().toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createSourceFileWithModule() throws IOException {
        final var module = symbolTable.enterModule("my.module");

        final var packageSymbol = symbolTable.enterPackage(module, "org.some");
        packageSymbol.markExists();
        module.addVisiblePackage(
                packageSymbol.getQualifiedName(),
                packageSymbol
        );


        final var sourceFile = filer.createSourceFile("my.module/org.some.MyClass");

        try (var ignored = sourceFile.openOutputStream()) {
            final var sourcePath = tempDir.resolve("org/some/MyClass.java");
            assertTrue(Files.exists(sourcePath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createSourceFile() throws IOException {
        final var module = SymbolTable.NO_MODULE;
        final var packageSymbol = symbolTable.enterPackage(module, "org.some");
        packageSymbol.markExists();
        module.addVisiblePackage(
                packageSymbol.getQualifiedName(),
                packageSymbol
        );

        final var sourceFile = filer.createSourceFile("org.some.MyClass");
        try (var ignored = sourceFile.openOutputStream()) {
            final var sourcePath = tempDir.resolve("org/some/MyClass.java");
            assertTrue(Files.exists(sourcePath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createClassFile() throws IOException {
        final var module = SymbolTable.NO_MODULE;
        final var packageSymbol = symbolTable.enterPackage(module, "org.some");
        packageSymbol.markExists();
        module.addVisiblePackage(
                packageSymbol.getQualifiedName(),
                packageSymbol
        );

        final var classFile = filer.createClassFile("org.some.MyClass");
        try (var ignored = classFile.openOutputStream()) {
            final var clasPath = tempDir.resolve("org/some/MyClass.class");
            assertTrue(Files.exists(clasPath), String.format("file %s doesn't exists ", clasPath.toString()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createResource() throws IOException {
        final var module = SymbolTable.NO_MODULE;
        final var packageSymbol = symbolTable.enterPackage(module, "org.some");
        packageSymbol.markExists();
        module.addVisiblePackage(
                packageSymbol.getQualifiedName(),
                packageSymbol
        );

        final var classFile = filer.createResource(
                StandardLocation.CLASS_OUTPUT,
                "org.some",
                "MyClass.properties"
        );
        try (var ignored = classFile.openOutputStream()) {
            final var clasPath = tempDir.resolve("org/some/MyClass.properties");
            assertTrue(Files.exists(clasPath));
            final var resource = filer.getResource(
                    StandardLocation.CLASS_OUTPUT,
                    "org.some",
                    "MyClass.properties"
            );
            assertNotNull(resource);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}