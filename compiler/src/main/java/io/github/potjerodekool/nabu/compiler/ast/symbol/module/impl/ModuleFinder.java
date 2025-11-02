package io.github.potjerodekool.nabu.compiler.ast.symbol.module.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ModuleFinder {

    private final SymbolTable symbolTable;
    private final FileManager fileManager;
    private final List<StandardLocation> moduleLocations = List.of(
            StandardLocation.MODULE_SOURCE_PATH,
            StandardLocation.UPGRADE_MODULE_PATH,
            StandardLocation.SYSTEM_MODULES,
            StandardLocation.MODULE_PATH
    );

    ModuleFinder(final SymbolTable symbolTable,
                 final FileManager fileManager) {
        this.symbolTable = symbolTable;
        this.fileManager = fileManager;
    }

    public ModuleSymbol findModule(final String name) {
        return findModule(symbolTable.enterModule(name));
    }

    public ModuleSymbol findModule(final ModuleSymbol moduleSymbol) {
        if (!moduleSymbol.isError()
                && moduleSymbol.getSourceLocation() == null
                && moduleSymbol.getClassLocation() == null) {
            final var modules = scanModulePath(moduleSymbol);

            if (modules.isEmpty()) {
                moduleSymbol.setError(true);
            } else if (modules.size() == 1) {
                final var foundModule = modules.getFirst();
                moduleSymbol.setSourceLocation(foundModule.getSourceLocation());
                moduleSymbol.setClassLocation(foundModule.getClassLocation());
            }
        }

        if (!moduleSymbol.isError()
                && moduleSymbol.getModuleInfo().getSourceFile() == null
                && moduleSymbol.getModuleInfo().getClassFile() == null) {
            findModuleInfo(moduleSymbol);
        }

        return moduleSymbol;
    }

    public List<ModuleSymbol> scanModulePath(final ModuleSymbol moduleToFind) {
        final var result = new ArrayList<ModuleSymbol>();
        final var findName = moduleToFind != null
                ? moduleToFind.getSimpleName()
                : null;

        final var locations = findLocations(findName);

        if (!locations.isEmpty()) {
            locations.forEach(location -> {
                final var moduleName = fileManager.resolveModuleName(location);
                final var module = symbolTable.enterModule(moduleName);

                if (location.isSourceLocation()) {
                    module.setSourceLocation(location);
                } else if (location.isClassLocation()) {
                    module.setClassLocation(location);
                }
                result.add(module);
            });
        }

        return result;
    }

    private void findModuleInfo(final ModuleSymbol moduleSymbol) {
        final var location = moduleSymbol.getClassLocation();
        final var fileObject = fileManager.getFileObject(
                location,
                moduleSymbol.getModuleInfo().getSimpleName());

        if (fileObject != null) {
            moduleSymbol.moduleInfo.setClassFile(fileObject);
        }
    }

    public List<FileManager.Location> findLocations(final String moduleNameToFind) {
        return moduleLocations.stream()
                .flatMap(location -> {
                    final var locations = fileManager.listLocationsForModules(location);

                    if (locations != null) {
                        return StreamSupport.stream(locations.spliterator(), false);
                    } else {
                        return Stream.empty();
                    }
                })
                .flatMap(Collection::stream)
                .filter(location ->
                        moduleNameToFind == null
                                || moduleNameToFind.equals(fileManager.resolveModuleName(location)))
                .toList();
    }

}
