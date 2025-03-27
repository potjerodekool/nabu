package io.github.potjerodekool.nabu.compiler.io;

import io.github.potjerodekool.nabu.compiler.CompilerOption;
import io.github.potjerodekool.nabu.compiler.io.FileManager.Location;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public abstract class BasicLocationHandler extends LocationHandler {

    private final Set<CompilerOption> supportedCompilerOptions;
    private final Location location;

    public BasicLocationHandler(final Location location,
                                final CompilerOption... supportedCompilerOptions) {
        this.location = location;
        this.supportedCompilerOptions = supportedCompilerOptions.length == 0
                ? EnumSet.noneOf(CompilerOption.class)
                : EnumSet.copyOf(Arrays.asList(supportedCompilerOptions));
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
