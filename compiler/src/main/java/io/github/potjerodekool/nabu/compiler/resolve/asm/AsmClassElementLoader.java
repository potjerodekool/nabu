package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.*;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.nio.file.Path;

public class AsmClassElementLoader implements ClassElementLoader, AutoCloseable {

    private final ClassPath classPath = new ClassPath();

    private final SymbolTable symbolTable;

    private final Types types;

    public AsmClassElementLoader() {
        this(new SymbolTable());
    }

    public AsmClassElementLoader(final SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
        types = new Types(symbolTable);
    }

    @Override
    public ClassSymbol resolveClass(final String name) {
        final var internalName = ClassUtils.toInternalName(name);

        var type = this.symbolTable.getClassSymbol(internalName);

        if (type != null) {
            return type;
        }

        final var matchResultOptional = this.classPath.find(internalName);

        if (matchResultOptional.isEmpty()) {
            return null;
        }

        final var bytecode = matchResultOptional.get().data();
        return readClass(bytecode);
    }

    private ClassSymbol readClass(final byte[] bytecode) {
        return ClazzReader.read(bytecode, symbolTable, this);
    }

    @Override
    public void postInit() {
        loadJavaLang();
        loadBoxes();
    }

    private void loadJavaLang() {
        classPath.loadJavaLang(fileMatchResult -> readClass(fileMatchResult.data()));
    }

    private void loadBoxes() {
        resolveClass(Constants.BOOLEAN);
        resolveClass(Constants.BYTE);
        resolveClass(Constants.SHORT);
        resolveClass(Constants.INTEGER);
        resolveClass(Constants.LONG);
        resolveClass(Constants.CHARACTER);
        resolveClass(Constants.FLOAT);
        resolveClass(Constants.DOUBLE);
    }

    @Override
    public void addClassPathEntry(final Path path) {
        classPath.addClassPathEntry(path);
    }

    public Types getTypes() {
        return types;
    }

    @Override
    public TypeMirror resolveType(final String internalName) {
        final var clazz = resolveClass(internalName);
        return clazz != null
                ? types.getDeclaredType(clazz)
                : null;
    }

    @Override
    public PackageElement findOrCreatePackage(final String packageName) {
        return symbolTable.findOrCreatePackage(packageName);
    }

    @Override
    public void importJavaLang(final ImportScope importScope) {
        final var javaLangPackage = symbolTable.findPackage("java.lang");

        javaLangPackage.getEnclosedElements().stream()
                        .filter(it -> switch (it.getKind()) {
                            case CLASS, INTERFACE, ANNOTATION, RECORD, ENUM -> true;
                            default -> false;
                        }).forEach(importScope::define);
    }

    @Override
    public void close() {
        classPath.close();
    }
}
