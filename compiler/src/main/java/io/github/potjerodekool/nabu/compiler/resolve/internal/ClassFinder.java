package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.symbol.*;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.FileManager;
import io.github.potjerodekool.nabu.compiler.io.FileManager.Location;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.io.StandardLocation;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClazzReader;
import io.github.potjerodekool.nabu.compiler.resolve.scope.WritableScope;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class ClassFinder {

    private final SymbolTable symbolTable;
    private final FileManager fileManager;
    private final ClassSymbolLoader classElementLoader;
    private final Completer completer = this::complete;
    private final SourceTypeEnter sourceTypeEnter;
    private final CompilerContextImpl compilerContext;

    public ClassFinder(final SymbolTable symbolTable,
                       final FileManager fileManager,
                       final ClassSymbolLoader classElementLoader,
                       final CompilerContextImpl compilerContext) {
        this.symbolTable = symbolTable;
        this.fileManager = fileManager;
        this.classElementLoader = classElementLoader;
        this.sourceTypeEnter = new SourceTypeEnter(compilerContext);
        this.compilerContext = compilerContext;
    }

    public Completer getCompleter() {
        return completer;
    }

    protected EnumSet<FileObject.Kind> getPackageFileKinds() {
        return EnumSet.of(FileObject.Kind.CLASS, FileObject.Kind.SOURCE_NABU);
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
            ClazzReader.read(inputStream.readAllBytes(), symbolTable, classElementLoader, classSymbol, packageSymbol.getModuleSymbol());
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
    }

    private void scanModulePath(final ModuleSymbol moduleSymbol, final PackageSymbol packageSymbol) {
        final var location = moduleSymbol.getClassLocation();

        final var kinds = getPackageFileKinds();

        final var classKinds = EnumSet.copyOf(kinds);
        classKinds.remove(FileObject.Kind.SOURCE_NABU);

        final var files = list(location, packageSymbol.getFullName(), classKinds);

        fillInPackage(packageSymbol, location, files);
    }

    private void fillInPackage(final PackageSymbol packageSymbol, final Location location, final Iterable<? extends FileObject> files) {
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
                        clazz.setSourceFile(file);

                        if (file.getKind() != FileObject.Kind.SOURCE_NABU) {
                            enterSource(clazz);
                        }

                    }
                } else {
                    if (clazz.getClassFile() == null) {
                        clazz.setClassFile(file);
                    }
                }

                if (clazz.getEnclosingElement() == packageSymbol) {
                    members.define(clazz);
                }
            } else {
                throw new IllegalArgumentException("Invalid classname" + className);
            }
        });
    }

    private void enterSource(final ClassSymbol classSymbol) {
        final var enterClasses = new EnterClasses(
                compilerContext
        );
        final var sourceEnterClasses = new SourceEnterClasses(enterClasses);
        final var compilationUnit = sourceEnterClasses.enter(classSymbol);
        final var classes = compilationUnit.getClasses();
        final var classDeclaration = classes.getFirst();

        final var typeEnter = compilerContext.getTypeEnter();

        typeEnter.put(classSymbol, classDeclaration, compilationUnit);
        classSymbol.setCompleter(typeEnter);
    }

    private boolean isValidIdentifier(final String className) {
        if (Character.isDigit(className.charAt(0))) {
            return false;
        }

        for (final var c : className.toCharArray()) {
            if (!(Character.isDigit(c)
                    || Character.isLetter(c)
                    || '$' == c
                    || '_' == c
            )) {
                return false;
            }
        }

        return true;
    }

    private void scanUserPaths(final PackageSymbol packageSymbol,
                               final boolean includeSourcePath) {
        final var kinds = EnumSet.of(
                FileObject.Kind.CLASS,
                FileObject.Kind.SOURCE_NABU,
                FileObject.Kind.SOURCE_JAVA
        );

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

    private Iterable<? extends FileObject> list(final Location location, final String packageName, final EnumSet<FileObject.Kind> kinds) {
        return fileManager.list(location, packageName, kinds);
    }

}
