package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ErrorSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;

public class AsmClassElementLoader implements ClassElementLoader, AutoCloseable {

    private final SymbolTable symbolTable;

    public AsmClassElementLoader(final CompilerContextImpl compilerContext) {
        compilerContext.put(new CompilerContextImpl.Key<>(), this);
        this.symbolTable = SymbolTable.getInstance(compilerContext);
    }

    @Override
    public TypeElement loadClass(final ModuleElement moduleElement,
                                 final String name) {
        final var flatName = Symbol.createFlatName(name);
        final var module = moduleElement != null
                ? (ModuleSymbol) moduleElement
                : symbolTable.getUnnamedModule();

        final var packageName = resolvePackagePart(flatName);

        if (packageName == null) {
            return createError(name);
        }

        final var packageSymbol = symbolTable.lookupPackage(
                module,
                packageName
        );

        packageSymbol.complete();

        if (!packageSymbol.exists()) {
            return createError(name);
        }

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
            return symbolTable.enterClass(
                    packageModule,
                    shortName,
                    packageSymbol
            );
        }
    }

    private ErrorSymbol createError(final String name) {
        /*
        final var simpleNameStart = name.lastIndexOf('.') + 1;
        final var simpleName = name.substring(simpleNameStart);
        return new ErrorSymbol(simpleName);
        */
        return null;
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
    public void close() {
    }
}
