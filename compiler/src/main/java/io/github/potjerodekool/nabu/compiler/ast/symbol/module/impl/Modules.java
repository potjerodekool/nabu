package io.github.potjerodekool.nabu.compiler.ast.symbol.module.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Completer;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.tools.FileManager;

import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;

import java.util.*;

public class Modules {

    private static final CompilerContextImpl.Key<Modules> KEY = new CompilerContextImpl.Key<>();

    private final SymbolTable symbolTable;
    private final ModuleCompleter moduleCompleter;
    private final UnnamedModuleCompleter unnamedModuleCompleter;
    private final Set<ModuleSymbol> allModules = new HashSet<>();

    public Modules(final CompilerContextImpl compilerContext) {
        compilerContext.put(KEY, this);

        this.symbolTable = SymbolTable.getInstance(compilerContext);
        final var fileManager = compilerContext.get(FileManager.class);
        final var classElementLoader = compilerContext.getClassElementLoader();

        this.moduleCompleter = new ModuleCompleter(
                new ModuleFinder(
                        symbolTable,
                        fileManager
                ),
                symbolTable,
                classElementLoader,
                compilerContext
        );
        this.unnamedModuleCompleter = new UnnamedModuleCompleter(
                this,
                symbolTable
        );
    }

    public static Modules getInstance(final CompilerContextImpl compilerContext) {
        var instance = compilerContext.get(KEY);

        if (instance == null) {
            instance = new Modules(compilerContext);
        }

        return instance;
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

