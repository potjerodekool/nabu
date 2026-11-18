package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
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

        final Path javaHome = javaHome();
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

    /**
     * The Java home whose modules are the system modules: the value of the SYSTEM option when it is one (it has
     * {@code lib/modules}, which the {@code jrt:} file system reads), otherwise the JDK that runs the compiler. A
     * SYSTEM path that is not a Java home used to end in a null pointer exception far from the cause (no module,
     * so no {@code java.lang.Object}); now it is said and the running JDK is used.
     */
    private Path javaHome() {
        final Path running = FileSystems.getDefault().getPath(System.getProperty("java.home"));
        if (systemValue == null) {
            return running;
        }
        final Path requested = Paths.get(systemValue);
        if (Files.isRegularFile(requested.resolve("lib").resolve("modules"))) {
            return requested;
        }
        LOGGER.log(LogLevel.WARN, "The system modules option " + requested
                + " is not a Java home (it has no lib/modules): using the modules of the JDK that runs the compiler, "
                + running);
        return running;
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
