package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.tools.FileManager.Location;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

public class SystemModulesLocationHandler extends BasicLocationHandler implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(SystemModulesLocationHandler.class.getName());

    private final List<FileSystem> openFileSystems = new ArrayList<>();
    private boolean isInit = false;
    private final ModuleTable moduleTable = new ModuleTable();
    private String systemValue;

    public SystemModulesLocationHandler() {
        super(StandardLocation.SYSTEM_MODULES, CompilerOption.SYSTEM);
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules() {
        initModules();
        return Collections.singleton(moduleTable.locations());
    }

    private void initModules() {
        if (isInit) {
            return;
        }
        isInit = true;

        final Path javaHome = systemValue != null
                ? Paths.get(systemValue)
                : FileSystems.getDefault().getPath(System.getProperty("java.home"));
        URI jrtURI = URI.create("jrt:/");

        Map<String, String> attrMap =
                Collections.singletonMap("java.home", javaHome.toString());
        try {
            final var jrtfs = FileSystems.newFileSystem(jrtURI, attrMap);
            openFileSystems.add(jrtfs);
            final var modules = jrtfs.getPath("/modules");

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(modules, Files::isDirectory)) {
                for (Path module : stream) {
                    final var moduleName = module.getFileName().toString();
                    moduleTable.add(new ModuleLocationHandler(
                            getLocation().getName(),
                            moduleName,
                            module,
                            false,
                            false,
                            true
                    ));
                }
            }
        } catch (final IOException e) {
            LOGGER.log(LogLevel.WARN, e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        for (final var openFileSystem : openFileSystems) {
            openFileSystem.close();
        }
    }

    @Override
    public void processOption(final CompilerOption compilerOption, final String value) {
        super.processOption(compilerOption, value);
        if (compilerOption == CompilerOption.SYSTEM) {
            this.systemValue = value;
        }
    }
}
