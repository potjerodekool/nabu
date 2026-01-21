package io.github.potjerodekool.nabu.compiler.annotation.processing;

import io.github.potjerodekool.nabu.compiler.annotation.tools.JavacFileObject;
import io.github.potjerodekool.nabu.compiler.annotation.tools.JavacFillerFileObject;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.util.Elements;
import io.github.potjerodekool.nabu.util.Pair;

import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

public class JavacFiler implements Filer {

    private final Logger logger = Logger.getLogger(getClass().getName());

    private final SymbolTable symbolTable;
    private final Elements elements;
    private final FileManager fileManager;

    private final io.github.potjerodekool.nabu.tools.FileObject.Kind SOURCE =
            new io.github.potjerodekool.nabu.tools.FileObject.Kind(".java", true);

    private final Set<String> generatedSourceFiles = new LinkedHashSet<>();
    private final Set<String> generatedClassFiles = new LinkedHashSet<>();

    public JavacFiler(final SymbolTable symbolTable,
                      final Elements elements, final FileManager fileManager) {
        this.symbolTable = symbolTable;
        this.elements = elements;
        this.fileManager = fileManager;
    }

    @Override
    public JavaFileObject createSourceFile(final CharSequence nameAndModule,
                                           final Element... originatingElements) throws IOException {
        final var moduleAndPackageName = resolveModule(nameAndModule);

        return createSourceOrClassFile(
                moduleAndPackageName.first(),
                JavaFileObject.Kind.SOURCE,
                moduleAndPackageName.second());
    }

    private JavaFileObject createSourceOrClassFile(final ModuleSymbol moduleSymbol,
                                                   final JavacFileObject.Kind kind,
                                                   final String name) throws IOException {
        final io.github.potjerodekool.nabu.tools.FileObject.Kind nabuKind;
        final FileManager.Location location;
        final Consumer<String> onCloseCallback;

        if (JavaFileObject.Kind.SOURCE == kind) {
            nabuKind = SOURCE;
            location = StandardLocation.SOURCE_OUTPUT;
            onCloseCallback = this::addGeneratedSourceFile;
        } else {
            nabuKind = io.github.potjerodekool.nabu.tools.FileObject.CLASS_KIND;
            location = StandardLocation.CLASS_OUTPUT;
            onCloseCallback = this::addGeneratedClassFile;
        }

        final var fileObject = fileManager.getJavaFileForOutputForOriginatingFiles(
                location,
                name,
                nabuKind
        );

        return new JavacFillerFileObject(fileObject, onCloseCallback);
    }

    private void addGeneratedSourceFile(final String path) {
        logger.log(LogLevel.INFO, "addGeneratedSourceFile " + path);
        this.generatedSourceFiles.add(path);
    }

    private void addGeneratedClassFile(final String path) {
        logger.log(LogLevel.INFO, "addGeneratedClassFile " + path);
        this.generatedClassFiles.add(path);
    }

    @Override
    public JavaFileObject createClassFile(final CharSequence nameAndModule,
                                          final Element... originatingElements) throws IOException {
        final var moduleAndPackageName = resolveModule(nameAndModule);

        return createSourceOrClassFile(
                moduleAndPackageName.first(),
                JavaFileObject.Kind.CLASS,
                moduleAndPackageName.second());
    }

    @Override
    public FileObject createResource(final JavaFileManager.Location location,
                                     final CharSequence moduleAndPkg,
                                     final CharSequence relativeName,
                                     final Element... originatingElements) throws IOException {
        final var relativeNameString = relativeName.toString();
        final var separatorIndex = relativeNameString.lastIndexOf('.');
        final var subName = separatorIndex == -1
                ? relativeNameString
                : relativeNameString.substring(0, separatorIndex);
        final var className = moduleAndPkg + "." + subName;

        final var resolved = resolve(location, className);
        final var resolvedLocation = resolved.location();
        final var packageName = resolved.name();
        final StandardLocation standardLocation;

        if (resolvedLocation == javax.tools.StandardLocation.CLASS_OUTPUT) {
            standardLocation = StandardLocation.CLASS_OUTPUT;
        } else if (resolvedLocation == javax.tools.StandardLocation.SOURCE_OUTPUT) {
            standardLocation = StandardLocation.SOURCE_OUTPUT;
        } else {
            throw new IllegalArgumentException();
        }

        final var fileObject = fileManager.getFileForOutputForOriginatingFiles(
                standardLocation,
                packageName,
                relativeName.toString()
        );

        return new JavacFillerFileObject(fileObject, (path) -> {});
    }

    private LocationModuleAnName resolve(final JavaFileManager.Location location,
                                         final CharSequence moduleAndPkg) throws FilerException {
        final var moduleAndPkgString = moduleAndPkg.toString();
        final var moduleSeparator = moduleAndPkgString.indexOf('/');

        if (moduleSeparator == -1) {
            final var pair = resolve(moduleAndPkgString);
            final var separatorIndex = moduleAndPkgString.lastIndexOf('.');
            final var packageName = moduleAndPkgString.substring(0, separatorIndex);

            return new LocationModuleAnName(
                    location,
                    pair.first(),
                    packageName
            );
        }

        throw new TodoException();
    }

    @Override
    public FileObject getResource(final JavaFileManager.Location location,
                                  final CharSequence moduleAndPkg,
                                  final CharSequence relativeName) throws IOException {
        final var relativeNameString = relativeName.toString();
        final var separatorIndex = relativeNameString.indexOf('.');
        final var name = relativeNameString.substring(0, separatorIndex);

        final var moduleAndPackageName = resolveModule(moduleAndPkg + "." + name);

        return new JavacFileObject(
                moduleAndPackageName.first(),
                moduleAndPackageName.second(),
                null
        );
    }

    private Pair<ModuleSymbol, String> resolveModule(final CharSequence moduleAndPackage) throws FilerException {
        final var moduleAndPackageString = moduleAndPackage.toString();
        final var moduleSeparator = moduleAndPackageString.indexOf('/');
        String moduleName;
        String packageName;

        if (moduleSeparator == -1) {
            final var pair = resolve(moduleAndPackageString);
            final var module = pair.first();
            packageName = pair.second();

            if (module != null) {
                return new Pair<>(module, moduleAndPackageString);
            } else {
                throw new FilerException(
                        String.format("Failed to resolve module for package %s", packageName)
                );
            }
        } else {
            moduleName = moduleAndPackageString.substring(0, moduleSeparator);
            packageName = moduleAndPackageString.substring(moduleSeparator + 1);
        }

        final var module = symbolTable.getModule(moduleName);

        if (module == null) {
            throw new FilerException(String.format(
                    "Module %s does not exists.",
                    moduleName
            ));
        }

        return new Pair<>(module, packageName);
    }

    private Pair<ModuleSymbol, String> resolve(final String moduleAndPackage) {
        final var lastDot = moduleAndPackage.lastIndexOf('.');
        final String packageName = lastDot != -1
                ? moduleAndPackage.substring(0, lastDot)
                : "";
        var module = resolveModuleByPackageName(packageName);

        if (module != null) {
            return new Pair<>(module, moduleAndPackage);
        } else {
            module = symbolTable.getUnnamedModule();
            return new Pair<>(module, moduleAndPackage);
        }
    }

    private ModuleSymbol resolveModuleByPackageName(final String packageName) {
        final var packageSymbol = elements.getPackageElement(packageName);

        if (packageSymbol != null && packageSymbol.getModuleSymbol() != symbolTable.getUnnamedModule()) {
            return (ModuleSymbol) packageSymbol.getModuleSymbol();
        }

        return null;
    }

    public Set<String> getGeneratedSourceFiles() {
        return generatedSourceFiles;
    }

    public Set<String> getGeneratedClassFiles() {
        return generatedClassFiles;
    }

    public void prepareForRound() {
        this.generatedSourceFiles.clear();
        this.generatedClassFiles.clear();
    }
}

record LocationModuleAnName(JavaFileManager.Location location,
                            ModuleSymbol module,
                            String name) {}
