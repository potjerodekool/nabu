package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.FileManager;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class OutputLocationHandler extends BasicLocationHandler {

    private Path outputDirectory;

    public OutputLocationHandler(final FileManager.Location location,
                                 final CompilerOption... supportedCompilerOptions) {
        super(location, supportedCompilerOptions);
    }

    @Override
    public void processOption(final CompilerOption compilerOption, final String value) {
        if (!getSupportedOptions().contains(compilerOption)) {
            return;
        }

        outputDirectory = value == null ? null : getPath(value);
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    private Path getPath(final String first,
                         final String... more) {
        try {
            return Paths.get(first, more);
        } catch (final InvalidPathException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
