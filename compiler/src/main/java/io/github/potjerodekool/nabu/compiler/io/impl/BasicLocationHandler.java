package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.tools.FileManager.Location;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

public abstract class BasicLocationHandler extends LocationHandler {

    private final Set<CompilerOption> supportedCompilerOptions;
    private final FileManager.Location location;

    public BasicLocationHandler(final Location location,
                                final CompilerOption... supportedCompilerOptions) {
        this.location = location;
        this.supportedCompilerOptions = supportedCompilerOptions.length == 0
                ? Collections.emptySet()
                : Set.copyOf(Arrays.asList(supportedCompilerOptions));
    }

    public Location getLocation() {
        return location;
    }

    public Set<CompilerOption> getSupportedOptions() {
        return supportedCompilerOptions;
    }

    public void processOption(final CompilerOption compilerOption,
                              final String value) {
    }
}
