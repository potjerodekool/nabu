package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.tools.FileManager;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public class ModuleLocationHandler extends LocationHandler implements FileManager.Location {

    private final String name;
    private final String moduleName;
    private final Path modulePath;
    private final boolean output;
    private final boolean isSourceLocation;
    private final boolean isClassLocation;
    private Collection<Path> searchPath;

    public ModuleLocationHandler(final String name,
                                 final String moduleName,
                                 final Path modulePath,
                                 final boolean output,
                                 final boolean isSourceLocation,
                                 final boolean isClassLocation) {
        this.name = name;
        this.moduleName = moduleName;
        this.modulePath = modulePath;
        this.output = output;
        this.isSourceLocation = isSourceLocation;
        this.isClassLocation = isClassLocation;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getModuleName() {
        return moduleName;
    }

    @Override
    public boolean isOutputLocation() {
        return output;
    }

    public Collection<Path> getSearchPath() {
        return searchPath != null
                ? searchPath
                : List.of();
    }

    @Override
    public String resolveModuleName() {
        return moduleName;
    }

    @Override
    public Collection<Path> getPaths() {
        return List.of(modulePath);
    }

    @Override
    public Path findPackage(final String packageName) {
        return modulePath.resolve(packageName.replace('.', '/'));
    }

    @Override
    public boolean isClassLocation() {
        return isClassLocation;
    }

    @Override
    public boolean isSourceLocation() {
        return isSourceLocation;
    }
}
