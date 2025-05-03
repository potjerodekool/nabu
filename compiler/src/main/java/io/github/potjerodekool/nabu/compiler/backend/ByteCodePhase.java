package io.github.potjerodekool.nabu.compiler.backend;

import io.github.potjerodekool.nabu.compiler.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.ErrorCapture;
import io.github.potjerodekool.nabu.compiler.backend.generate.ByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.AsmdByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class ByteCodePhase {

    private static final Logger LOGGER = Logger.getLogger(ByteCodePhase.class.getName());

    private ByteCodePhase() {
    }

    public static int generate(final List<CompilationUnit> compilationUnits,
                               final CompilerOptions compilerOptions,
                               final ErrorCapture errorCapture,
                               final Path targetDirectory) {
        if (errorCapture.getErrorCount() > 0) {
            LOGGER.log(LogLevel.ERROR,
                    "Compilation failed with " + errorCapture.getErrorCount() + " errors"
            );
            return 1;
        }

        compilationUnits.forEach(compilationUnit ->
                doGenerate(compilationUnit, compilerOptions, targetDirectory));

        return 0;
    }

    private static void doGenerate(final CompilationUnit compilationUnit,
                                   final CompilerOptions compilerOptions,
                                   final Path targetDirectory) {
        final ByteCodeGenerator generator = new AsmdByteCodeGenerator(
                compilerOptions
        );

        final var moduleDeclaration = compilationUnit.getModuleDeclaration();
        final var classes = compilationUnit.getClasses();
        final String packageName;
        final String name;

        if (moduleDeclaration != null) {
            generator.generate(moduleDeclaration, null);
            name = "module-info";
            packageName = null;
        } else if (!classes.isEmpty()) {
            final var clazz = classes.getFirst();
            final var packageDeclaration = compilationUnit.getPackageDeclaration();
            generator.generate(clazz, null);
            name = clazz.getSimpleName();
            packageName = packageDeclaration.getQualifiedName();
        } else {
            return;
        }

        final var bytecode = generator.getBytecode();
        final var outputPath = Path.of(name + ".class");

        Path outputDirectory;

        if (packageName != null) {
            final var packagePath = Paths.get(packageName
                    .replace('.', File.separatorChar));
            outputDirectory = targetDirectory.resolve(packagePath);
        } else {
            outputDirectory = targetDirectory;
        }

        final var path = outputDirectory.resolve(outputPath);

        try {
            deleteIfExists(path);
            createDirectories(outputDirectory);
            Files.write(path, bytecode);
            LOGGER.log(
                    LogLevel.INFO,
                    String.format("Generated %s", path.toAbsolutePath())
            );
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void createDirectories(final Path path) throws IOException {
        Files.createDirectories(path);
    }

    private static void deleteIfExists(final Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var directoryStream = Files.newDirectoryStream(path)) {
                directoryStream.forEach(subPath -> {
                    try {
                        deleteIfExists(subPath);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } else {
            Files.deleteIfExists(path);
        }
    }
}
