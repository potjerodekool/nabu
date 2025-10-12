package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.tools.FileManager.Location;

import java.nio.file.Path;
import java.util.*;

public abstract class LocationHandler {

    public Iterable<Set<Location>> listLocationsForModules() {
        return null;
    }

    public Location getLocationForModule(final String moduleName) {
        return null;
    }

    public String resolveModuleName() {
        return null;
    }

    public Collection<Path> getPaths() {
        return List.of();
    }

    public Path findPackage(String packageName) {
        return null;
    }

}
