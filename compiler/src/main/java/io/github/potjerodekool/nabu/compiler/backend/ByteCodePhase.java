package io.github.potjerodekool.nabu.compiler.backend;

import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.impl.ErrorCapture;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.backend.generate.ByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.AsmByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.ModuleDeclaration;
import io.github.potjerodekool.nabu.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class ByteCodePhase {

    private static final Logger LOGGER = Logger.getLogger(ByteCodePhase.class.getName());

    public int generate(final List<CompilationUnit> compilationUnits,
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

    private void doGenerate(final CompilationUnit compilationUnit,
                            final CompilerOptions compilerOptions,
                            final Path targetDirectory) {


        final var moduleDeclaration = compilationUnit.getModuleDeclaration();
        final var classes = compilationUnit.getClasses();

        if (moduleDeclaration != null) {
            generateModule(compilerOptions, moduleDeclaration, targetDirectory);

        } else if (!classes.isEmpty()) {
            generateClass(compilerOptions, classes.getFirst(), targetDirectory);
        }
    }

    private void generateModule(final CompilerOptions compilerOptions,
                                final ModuleDeclaration moduleDeclaration,
                                final Path targetDirectory) {
        final ByteCodeGenerator generator = new AsmByteCodeGenerator(compilerOptions);
        generator.generate(moduleDeclaration, null);
        final var name = "module-info";
        doGenerate(null, name, generator, targetDirectory);
    }

    private void generateClass(final CompilerOptions compilerOptions,
                               final ClassDeclaration classDeclaration,
                               final Path targetDirectory) {
        final ByteCodeGenerator generator = new AsmByteCodeGenerator(compilerOptions);
        final var classSymbol = classDeclaration.getClassSymbol();

        try {
            final var packageSymbol = findPackageSymbol(classSymbol.getEnclosingElement());
            generator.generate(classDeclaration, null);
            final var packageName = packageSymbol.getQualifiedName();
            final var fileName = fileName(packageName, classSymbol);

            doGenerate(packageName, fileName, generator, targetDirectory);

            classDeclaration.getEnclosedElements().stream()
                    .flatMap(CollectionUtils.mapOnly(ClassDeclaration.class))
                    .forEach(enclosedElement ->
                            generateClass(compilerOptions, enclosedElement, targetDirectory));
        } catch (final Exception e) {
            LOGGER.log(LogLevel.ERROR, String.format(
                    "Failed to generate class for %s", classSymbol.getQualifiedName()),
                    e
            );
        }
    }

    private String fileName(final String packageName,
                            final TypeElement classSymbol) {
        final var flatName = classSymbol.getFlatName();
        return flatName.substring(packageName.length() + 1);

    }

    private PackageSymbol findPackageSymbol(final Element element) {
        if (element instanceof PackageSymbol packageSymbol) {
            return packageSymbol;
        } else {
            return findPackageSymbol(element.getEnclosingElement());
        }
    }

    private void doGenerate(final String packageName,
                            final String name,
                            final ByteCodeGenerator generator,
                            final Path targetDirectory) {
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

    private void createDirectories(final Path path) throws IOException {
        Files.createDirectories(path);
    }

    private void deleteIfExists(final Path path) throws IOException {
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
