package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.*;
import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.spi.SourceParser;
import io.github.potjerodekool.nabu.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.tools.FileManager.Location;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClazzReader;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;

import java.io.IOException;
import java.util.*;

public class ClassFinder {

    private static final CompilerContextImpl.Key<ClassFinder> KEY = new CompilerContextImpl.Key<>();

    private final SymbolTable symbolTable;
    private final FileManager fileManager;
    private final Completer completer = this::complete;
    private final SourceTypeEnter sourceTypeEnter;
    private final CompilerContextImpl compilerContext;
    private final PluginRegistry pluginRegistry;

    public ClassFinder(final CompilerContextImpl compilerContext) {
        compilerContext.put(KEY, this);
        this.fileManager = compilerContext.get(FileManager.class);
        this.symbolTable = SymbolTable.getInstance(compilerContext);
        this.sourceTypeEnter = new SourceTypeEnter(compilerContext);
        this.compilerContext = compilerContext;
        this.pluginRegistry = compilerContext.getPluginRegistry();
    }

    public static ClassFinder getInstance(final CompilerContextImpl compilerContext) {
        var classFinder = compilerContext.get(KEY);

        if (classFinder == null) {
            classFinder = new ClassFinder(compilerContext);
        }

        return classFinder;
    }

    public Completer getCompleter() {
        return completer;
    }

    protected Set<FileObject.Kind> getPackageFileKinds() {
        return this.fileManager.allKinds();
    }

    private void complete(final Symbol symbol) {
        symbol.setCompleter(Completer.NULL_COMPLETER);

        if (symbol.getKind() == ElementKind.PACKAGE) {
            final var packageSymbol = (PackageSymbol) symbol;
            fillInPackage(packageSymbol);
        } else if (symbol.getKind().isDeclaredType()) {
            completeClass((ClassSymbol) symbol);
        }
    }

    private void completeClass(final ClassSymbol classSymbol) {
        completeOwner(classSymbol.getEnclosingElement());
        completeEnclosing(classSymbol);
        classSymbol.setMembers(new WritableScope());
        fillInClass(classSymbol);
    }

    private void completeEnclosing(final ClassSymbol classSymbol) {
        if (classSymbol.getEnclosingElement().getKind() == ElementKind.PACKAGE) {
            final var owner = classSymbol.getEnclosingElement();

            for (final var enclosingName : enclosingNames(shortName(classSymbol.getSimpleName()))) {
                var enclosing = (Symbol) owner.getMembers().resolve(enclosingName);

                if (enclosing == null) {
                    final var packageSymbol = findPackageSymbol(classSymbol);

                    if (packageSymbol != null) {
                        enclosing = symbolTable.getClassSymbol(packageSymbol.getModuleSymbol(), Symbol.createFlatName(owner, enclosingName));
                    }
                }

                if (enclosing != null) {
                    enclosing.complete();
                }
            }
        } else if (classSymbol.getEnclosingElement() instanceof ClassSymbol) {
            classSymbol.setNestingKind(NestingKind.MEMBER);
        }
    }

    private PackageSymbol findPackageSymbol(final ClassSymbol classSymbol) {
        final var enclosing = classSymbol.getEnclosingElement();

        if (enclosing instanceof PackageSymbol packageSymbol) {
            return packageSymbol;
        } else if (enclosing instanceof ClassSymbol enclosingClass) {
            return findPackageSymbol(enclosingClass);
        } else {
            return null;
        }
    }

    private static String shortName(final String name) {
        final var start = name.lastIndexOf('.') + 1;

        if (start == 0) {
            return name;
        } else {
            return name.substring(start);
        }
    }

    private static List<String> enclosingNames(final String name) {
        final var names = new ArrayList<String>();
        int offset = 0;
        int end;

        while ((end = name.indexOf('$', offset)) > 0) {
            names.add(name.substring(0, end));
            offset = end + 1;
        }

        return names;
    }

    private void completeOwner(final Symbol symbol) {
        if (symbol instanceof PackageSymbol packageSymbol) {
            packageSymbol.markExists();
        }

        if (symbol.getKind() != ElementKind.PACKAGE) {
            completeOwner(symbol.getEnclosingElement());
        }
        symbol.complete();
    }

    private void fillInClass(final ClassSymbol classSymbol) {
        if (classSymbol.getSourceFile() != null) {
            fillInClassFromSource(classSymbol);
        } else if (classSymbol.getClassFile() != null) {
            fillInClassFromClass(classSymbol);
        }
    }

    private void fillInClassFromClass(final ClassSymbol classSymbol) {
        final var packageSymbol = findPackageSymbol(classSymbol.getEnclosingElement());

        try (final var inputStream = classSymbol.getClassFile().openInputStream()) {
            ClazzReader.read(
                    inputStream.readAllBytes(),
                    symbolTable,
                    compilerContext,
                    classSymbol,
                    packageSymbol.getModuleSymbol()
            );
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void fillInClassFromSource(final ClassSymbol classSymbol) {
        sourceTypeEnter.fill(classSymbol);
    }

    private PackageSymbol findPackageSymbol(final Symbol symbol) {
        if (symbol instanceof PackageSymbol packageSymbol) {
            return packageSymbol;
        } else if (symbol == null) {
            return null;
        } else {
            return findPackageSymbol(symbol.getEnclosingElement());
        }
    }

    private void fillInPackage(final PackageSymbol packageSymbol) {
        if (packageSymbol.getMembers() == null) {
            final var members = new WritableScope();
            packageSymbol.setMembers(members);
        }

        final var module = packageSymbol.getModuleSymbol();

        if (module.getClassLocation() == StandardLocation.CLASS_PATH) {
            scanUserPaths(
                    packageSymbol,
                    module.getSourceLocation() == StandardLocation.SOURCE_PATH
            );

            //TODO TODO: Add support for user source files. For now, we only scan the module path.
            //User source files are currently not found, so we need to scan the module path as well.
            //Since those files are in the unnamed module they will be found on the module path.
            if (packageSymbol.getMembers().isEmpty()) {
                scanModulePath(module, packageSymbol);
            }
        } else {
            scanModulePath(module, packageSymbol);
        }

        if (!packageSymbol.getEnclosedElements().isEmpty()) {
            packageSymbol.markExists();
        }
    }

    private void scanModulePath(final ModuleSymbol moduleSymbol,
                                final PackageSymbol packageSymbol) {
        final var kinds = getPackageFileKinds();
        final var classKinds = this.fileManager.copyOf(kinds);
        final FileManager.Location location;

        if (moduleSymbol.getSourceLocation() != null) {
            classKinds.removeIf(kind -> !kind.isSource());
            location = moduleSymbol.getSourceLocation();
        } else {
            classKinds.removeIf(FileObject.Kind::isSource);
            location = moduleSymbol.getClassLocation();
        }
        final var files = list(location, packageSymbol.getFullName(), classKinds);

        fillInPackage(packageSymbol, location, files);
    }

    private void fillInPackage(final PackageSymbol packageSymbol,
                               final Location location,
                               final Iterable<? extends FileObject> files) {
        final var members = packageSymbol.getMembers();

        files.forEach(file -> {
            String binaryName = fileManager.resolveBinaryName(location, file);
            final var sepIndex = binaryName.lastIndexOf('.');
            final var className = binaryName.substring(sepIndex + 1);

            if (isValidIdentifier(className)
                    || "package-info".equals(className)) {
                final var clazz = symbolTable.enterClass(packageSymbol.getModuleSymbol(), className, packageSymbol);

                if (file.getKind().isSource()) {
                    if (clazz.getSourceFile() == null) {
                        clazz.setClassFile(null);
                        clazz.setSourceFile(file);
                        getSourceParser(file.getKind()).ifPresent(it -> enterSource(clazz, it));
                    }
                } else if (clazz.getSourceFile() == null) {
                    clazz.setClassFile(file);
                }

                if (samePackage(packageSymbol, clazz.getEnclosingElement())) {
                    members.define(clazz);
                }
            }
        });
    }

    private boolean samePackage(final PackageSymbol packageSymbol,
                                final Symbol symbol) {
        return symbol instanceof PackageSymbol otherPackage && packageSymbol.getQualifiedName().equals(otherPackage.getQualifiedName());
    }

    private Optional<? extends SourceParser> getSourceParser(final FileObject.Kind kind) {
        return this.pluginRegistry.getSourceParser(kind);
    }

    private void enterSource(final ClassSymbol classSymbol,
                             final SourceParser sourceParser) {
        final var compilationUnit = sourceParser.parse(
                classSymbol.getSourceFile(),
                compilerContext
        );
        final var classes = compilationUnit.getClasses();
        final var classDeclaration = (CClassDeclaration) classes.getFirst();
        classDeclaration.setClassSymbol(classSymbol);
        final var typeEnter = compilerContext.getTypeEnter();
        typeEnter.put(classSymbol, classDeclaration, compilationUnit);
        classSymbol.setCompleter(typeEnter);
    }

    private boolean isValidIdentifier(final String className) {
        if (className.isEmpty()) {
            return false;
        }
        int cp = className.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp)) {
            return false;
        }
        for (int i = Character.charCount(cp); i < className.length(); i += Character.charCount(cp)) {
            cp = className.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) {
                return false;
            }
        }
        return true;
    }

    private void scanUserPaths(final PackageSymbol packageSymbol,
                               final boolean includeSourcePath) {
        final var kinds = this.fileManager.allKinds();

        fillInPackage(
                packageSymbol,
                StandardLocation.CLASS_PATH,
                list(
                        StandardLocation.CLASS_PATH,
                        packageSymbol.getFullName(),
                        kinds
                )
        );

        if (includeSourcePath && fileManager.hasLocation(StandardLocation.SOURCE_PATH)) {
            fillInPackage(
                    packageSymbol,
                    StandardLocation.SOURCE_PATH,
                    list(
                            StandardLocation.SOURCE_PATH,
                            packageSymbol.getFullName(),
                            kinds
                    )
            );
        }
    }

    private Iterable<? extends FileObject> list(final Location location,
                                                final String packageName,
                                                final Set<FileObject.Kind> kinds) {
        return fileManager.list(location, packageName, kinds);
    }

}
