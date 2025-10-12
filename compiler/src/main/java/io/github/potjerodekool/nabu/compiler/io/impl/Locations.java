package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.tools.FileManager.Location;
import io.github.potjerodekool.nabu.tools.StandardLocation;

import java.nio.file.Path;
import java.util.*;

public class Locations implements AutoCloseable {

    private final Map<Location, BasicLocationHandler> handlersForLocation = new HashMap<>();
    private final Map<CompilerOption, BasicLocationHandler> handlerForOption = new HashMap<>();

    public Locations() {
        initLocationHandlers();
    }

    public void processOptions(final CompilerOptions options) {
        options.forEach((option, value) ->
                getHandlerForOption(option).ifPresent(handler -> handler.processOption(option, value)));
    }

    private Optional<BasicLocationHandler> getHandlerForOption(final CompilerOption compilerOption) {
        return Optional.ofNullable(this.handlerForOption.get(compilerOption));
    }

    private void initLocationHandlers() {
        final List<BasicLocationHandler> locationHandlers = List.of(
                new ClassPathLocationHandler(),
                new SimpleLocationHandler(StandardLocation.SOURCE_PATH, CompilerOption.SOURCE_PATH),
                new SystemModulesLocationHandler()
        );

        for (final var locationHandler : locationHandlers) {
            this.handlersForLocation.put(locationHandler.getLocation(), locationHandler);
        }

        for (final var locationHandler : locationHandlers) {
            for (final var supportedOption : locationHandler.getSupportedOptions()) {
                this.handlerForOption.put(
                        supportedOption,
                        locationHandler
                );
            }
        }
    }

    public FileManager.Location getLocationForModule(final FileManager.Location location,
                                                     final String moduleName) {
        final var handler = getLocationHandler(location);
        return handler != null
                ? handler.getLocationForModule(moduleName)
                : null;
    }

    public Iterable<Set<Location>> listLocationsForModules(final Location location) {
        final var locationHandler = getLocationHandler(location);

        if (locationHandler == null) {
            return null;
        } else {
            return locationHandler.listLocationsForModules();
        }
    }

    LocationHandler getLocationHandler(final Location location) {
        if (location == null) {
            return null;
        } else if (location instanceof LocationHandler locationHandler) {
            return locationHandler;
        } else {
            return handlersForLocation.get(location);
        }
    }

    public String resolveModuleName(final Location location) {
        final var handler = getLocationHandler(location);
        return handler != null
                ? handler.resolveModuleName()
                : null;
    }

    public Iterable<? extends Path> getLocationAsPaths(final Location location) {
        final var handler = getLocationHandler(location);

        if (handler == null) {
            return List.of();
        } else {
            return handler.getPaths();
        }
    }

    @Override
    public void close() {
        handlersForLocation.values().forEach(this::closeHandler);
    }

    private void closeHandler(final LocationHandler locationHandler) {
        if (locationHandler instanceof AutoCloseable autoCloseable) {
            try {
                autoCloseable.close();
            } catch (final Exception ignored) {
            }
        }
    }

    public Collection<Path> getLocation(final Location location) {
        final var locationHandler = getLocationHandler(location);

        if (locationHandler == null) {
            return List.of();
        } else {
            return locationHandler.getPaths();
        }
    }

    public boolean hasLocation(final Location location) {
        return handlersForLocation.containsKey(location);
    }
}

