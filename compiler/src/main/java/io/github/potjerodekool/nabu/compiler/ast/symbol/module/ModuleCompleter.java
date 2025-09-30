package io.github.potjerodekool.nabu.compiler.ast.symbol.module;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ModuleElement;
import io.github.potjerodekool.nabu.compiler.ast.symbol.*;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClazzReader;
import io.github.potjerodekool.nabu.compiler.resolve.internal.SymbolTable;

import java.io.IOException;
import java.util.List;

class ModuleCompleter implements Completer {

    private final ModuleFinder moduleFinder;
    private final SymbolTable symbolTable;
    private final ClassElementLoader loader;

    public ModuleCompleter(final ModuleFinder moduleFinder,
                           final SymbolTable symbolTable,
                           final ClassElementLoader loader) {
        this.moduleFinder = moduleFinder;
        this.symbolTable = symbolTable;
        this.loader = loader;
    }

    public ModuleFinder getModuleFinder() {
        return moduleFinder;
    }

    @Override
    public void complete(final Symbol sym) throws CompleteException {
        sym.setCompleter(Completer.NULL_COMPLETER);

        final var module = moduleFinder.findModule((ModuleSymbol) sym);

        if (module.isError()) {
            initErrModule(module);
        } else if ((module.getFlags() & Flags.AUTOMATIC_MODULE) != 0) {
            setupAutomaticModule(module);
        } else {
            try {
                module.getModuleInfo().complete();
            } catch (final CompleteException e) {
                module.setError(true);
                initErrModule(module);
                completeModule(module);
                throw e;
            }
        }

        if (module.getModuleInfo().getClassFile() == null
                || module.getModuleInfo().getClassFile().getKind() == FileObject.CLASS_KIND) {
            completeModule(module);
        }
    }

    private void completeModule(final ModuleSymbol module) {
        final var classFile = module.moduleInfo.getClassFile();

        try (final var inputStream = classFile.openInputStream()) {
            final var bytecode = inputStream.readAllBytes();
            ClazzReader.read(
                    bytecode,
                    symbolTable,
                    loader,
                    module.moduleInfo,
                    module
            );
            initVisiblePackages(module);
        } catch (final IOException ignored) {
        }
    }

    private void initVisiblePackages(final ModuleSymbol module) {
        module.getRequires().stream()
                .map(ModuleElement.RequiresDirective::getDependency)
                .flatMap(dependency -> dependency.getExports().stream())
                .filter(export -> export.getTargetModules().isEmpty()
                        || export.getTargetModules().contains(module))
                .map(ModuleElement.ExportsDirective::getPackage)
                .forEach(packageElement -> module.addVisiblePackage(packageElement.getQualifiedName(), (PackageSymbol) packageElement));
    }

    private void setupAutomaticModule(final ModuleSymbol module) {
        throw new TodoException();
    }

    private void initErrModule(final ModuleSymbol moduleSymbol) {
        moduleSymbol.setDirectives(List.of());
        moduleSymbol.setExports(List.of());
        moduleSymbol.setProvides(List.of());
        moduleSymbol.setRequires(List.of());
        moduleSymbol.setUses(List.of());
    }

    @Override
    public String toString() {
        return "mainCompleter";
    }
}
