package io.github.potjerodekool.nabu.compiler.io;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ModuleTable {

    private final Map<String, ModuleLocationHandler> nameToHandler = new HashMap<>();
    private final Map<Path, ModuleLocationHandler> pathToHandler = new HashMap<>();

    public Set<FileManager.Location> locations() {
        return nameToHandler.values().stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    public void add(final ModuleLocationHandler handler) {
        nameToHandler.put(handler.getModuleName(), handler);
        handler.getSearchPath().forEach(path -> pathToHandler.put(path, handler));
    }
}
