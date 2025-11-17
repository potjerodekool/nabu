package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.tools.FileManager;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ModuleTable {

    private final Map<String, ModuleLocationHandler> nameToHandler = new HashMap<>();

    public Set<FileManager.Location> locations() {
        return nameToHandler.values().stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    public void add(final ModuleLocationHandler handler) {
        nameToHandler.put(handler.getModuleName(), handler);
    }
}
