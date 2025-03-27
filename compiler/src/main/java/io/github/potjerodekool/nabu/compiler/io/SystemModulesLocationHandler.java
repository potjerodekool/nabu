package io.github.potjerodekool.nabu.compiler.io;

import io.github.potjerodekool.nabu.compiler.CompilerOption;
import io.github.potjerodekool.nabu.compiler.io.FileManager.Location;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

public class SystemModulesLocationHandler extends BasicLocationHandler implements AutoCloseable {

    private final List<FileSystem> openFileSystems = new ArrayList<>();
    private final ModuleTable moduleTable = new ModuleTable();

    public SystemModulesLocationHandler() {
        super(StandardLocation.SYSTEM_MODULES, CompilerOption.SYSTEM);
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules() {
        initModules();
        return Collections.singleton(moduleTable.locations());
    }

    private void initModules() {
        final Path javaHome = FileSystems.getDefault().getPath(System.getProperty("java.home"));

        URI jrtURI = URI.create("jrt:/");

        Map<String, String> attrMap =
                Collections.singletonMap("java.home", javaHome.toString());
        try{
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
                            false
                    ));
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        for (final var openFileSystem : openFileSystems) {
            openFileSystem.close();
        }
    }
}
