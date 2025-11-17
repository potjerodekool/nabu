package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.tools.StandardLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

public class ModuleSourcePathLocationHandler extends BasicLocationHandler implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ModuleSourcePathLocationHandler.class.getName());

    private boolean isInit = false;
    private final ModuleTable moduleTable = new ModuleTable();
    private String sourcePath;

    public ModuleSourcePathLocationHandler() {
        super(StandardLocation.MODULE_SOURCE_PATH, CompilerOption.MODULE_SOURCE_PATH);
    }

    @Override
    public Iterable<Set<FileManager.Location>> listLocationsForModules() {
        initModules();
        return Collections.singleton(moduleTable.locations());
    }

    private void initModules() {
        if (isInit) {
            return;
        }
        isInit = true;

        if (sourcePath != null) {
            final var modules = Paths.get(sourcePath);

            try (var stream = Files.newDirectoryStream(modules, Files::isDirectory)) {
                for (Path module : stream) {
                    final var moduleName = module.getFileName().toString();
                    moduleTable.add(new ModuleLocationHandler(
                            getLocation().getName(),
                            moduleName,
                            module,
                            false,
                            true,
                            false
                    ));
                }
            } catch (final IOException e) {
                LOGGER.log(LogLevel.WARN, e.getMessage());
            }
        }
    }

    @Override
    public void processOption(final CompilerOption compilerOption,
                              final String value) {
        if (CompilerOption.MODULE_SOURCE_PATH == compilerOption) {
            this.sourcePath = value;
        }
    }

    @Override
    public void close() {
    }
}
