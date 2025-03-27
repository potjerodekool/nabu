package io.github.potjerodekool.nabu.compiler.ast.symbol.module;

import io.github.potjerodekool.nabu.compiler.ast.element.ModuleElement;
import io.github.potjerodekool.nabu.compiler.ast.symbol.*;
import io.github.potjerodekool.nabu.compiler.resolve.internal.SymbolTable;

import java.util.HashSet;

class UnnamedModuleCompleter implements Completer {

    private final Modules modules;
    private final SymbolTable symbolTable;

    public UnnamedModuleCompleter(final Modules modules,
                                  final SymbolTable symbolTable) {
        this.modules = modules;
        this.symbolTable = symbolTable;
    }

    @Override
    public void complete(final Symbol symbol) throws CompleteException {
        symbol.setCompleter(Completer.NULL_COMPLETER);

        final var module = (ModuleSymbol) symbol;
        final var allModules = new HashSet<>(modules.allModules());
        allModules.remove(symbolTable.getUnnamedModule());
        allModules.forEach(Symbol::complete);
        initVisiblePackages(module, allModules);
    }

    private void initVisiblePackages(final ModuleSymbol module,
                                     final HashSet<ModuleSymbol> allModules) {
        allModules.stream()
                .flatMap(it -> it.getExports().stream())
                .map(ModuleElement.ExportsDirective::getPackage)
                .forEach(packageSymbol -> module.addVisiblePackage(
                        packageSymbol.getQualifiedName(),
                        (PackageSymbol) packageSymbol
                ));


    }
}
