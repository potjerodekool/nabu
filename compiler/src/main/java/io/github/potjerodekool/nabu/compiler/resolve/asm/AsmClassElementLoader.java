package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;

public class AsmClassElementLoader implements ClassSymbolLoader, AutoCloseable {

    private final SymbolTable symbolTable;

    private final TypesImpl types;

    public AsmClassElementLoader(final CompilerContextImpl compilerContext) {
        this.symbolTable = SymbolTable.getInstance(compilerContext);
        this.types = new TypesImpl(symbolTable);
    }

    @Override
    public TypeElement loadClass(final ModuleElement moduleElement,
                                 final String name) {
        final var flatName = Symbol.createFlatName(name);
        final var module = moduleElement != null
                ? (ModuleSymbol) moduleElement
                : symbolTable.getJavaBase();

        final var packageName = resolvePackagePart(flatName);

        if (packageName == null) {
            return null;
        }

        final var packageSymbol = symbolTable.lookupPackage(
                module,
                packageName
        );
        final var packageModule = packageSymbol.getModuleSymbol();

        if (flatName.contains("$")) {
            var previous = (TypeSymbol) packageSymbol;
            var symbol = previous;

            final var className = resolveShortPart(flatName);
            final var names = className.split("\\$");

            for (final String subName : names) {
                symbol = symbolTable.enterClass(
                        packageModule,
                        subName,
                        previous
                );
                previous = symbol;
            }

            return (TypeElement) symbol;
        } else {
            final var shortName = resolveShortPart(flatName);
            final var clazz = symbolTable.enterClass(
                    packageModule,
                    shortName,
                    packageSymbol
            );
            return clazz;
        }
    }

    private String resolvePackagePart(final String name) {
        final var sep = name.lastIndexOf('.');

        if (sep == -1) {
            return null;
        } else {
            return name.substring(0, sep);
        }
    }

    private String resolveShortPart(final String name) {
        final var sep = name.lastIndexOf('.') + 1;

        if (sep == 0) {
            return name;
        } else {
            return name.substring(sep);
        }
    }

    @Override
    public TypesImpl getTypes() {
        return types;
    }

    @Override
    public PackageElement findOrCreatePackage(final ModuleElement moduleElement,
                                              final String packageName) {
        return symbolTable.lookupPackage((ModuleSymbol) moduleElement, packageName);
    }

    @Override
    public void importJavaLang(final ImportScope importScope) {
        final var javaLangPackage = symbolTable.lookupPackage(symbolTable.getUnnamedModule(), "java.lang");
        javaLangPackage.getMembers().elements().forEach(importScope::define);
    }

    @Override
    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    @Override
    public void close() {
    }
}
