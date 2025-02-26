package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.*;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.type.Types;

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
        types = new TypesImpl(this, symbolTable);
    }

    @Override
    public TypeElement resolveClass(final String name) {
        final var internalName = ClassUtils.toInternalName(name);

        var element = this.symbolTable.getClassSymbol(internalName);

        if (element != null) {
            return element;
        }

        final var matchResultOptional = this.classPath.find(internalName);

        if (matchResultOptional.isPresent()) {
            final var bytecode = matchResultOptional.get().data();
            element = readClass(bytecode);
        }

        return element;
    }


    private TypeElement readClass(final byte[] bytecode) {
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

    @Override
    public Types getTypes() {
        return types;
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
