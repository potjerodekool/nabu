package io.github.potjerodekool.nabu.compiler.ast.symbol.module.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Completer;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.FileManager;

import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;

import java.util.*;

public class Modules {

    private final SymbolTable symbolTable;
    private final ModuleCompleter moduleCompleter;
    private final UnnamedModuleCompleter unnamedModuleCompleter;
    private final Set<ModuleSymbol> allModules = new HashSet<>();

    public Modules(final SymbolTable symbolTable,
                   final FileManager fileManager,
                   final ClassElementLoader loader) {
        this.symbolTable = symbolTable;
        this.moduleCompleter = new ModuleCompleter(
                new ModuleFinder(
                        symbolTable,
                        fileManager
                ),
                symbolTable,
                loader
        );
        this.unnamedModuleCompleter = new UnnamedModuleCompleter(
                this,
                symbolTable
        );
    }

    public Completer getModuleCompleter() {
        return moduleCompleter;
    }

    public Completer getUnnamedModuleCompleter() {
        return unnamedModuleCompleter;
    }

    public Set<ModuleSymbol> allModules() {
        return allModules;
    }

    public ModuleSymbol getModuleByName(final String name) {
        final var module = symbolTable.getModule(name);
        return allModules().contains(module)
                ? module
                : null;
    }

    public void initAllModules() {
        symbolTable.getJavaBase().complete();
        final var modules = moduleCompleter.getModuleFinder().scanModulePath(null);
        this.allModules.addAll(modules);
    }

}

