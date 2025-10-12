package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.FileManager;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

public class SimpleLocationHandler extends BasicLocationHandler {

    protected Collection<Path> searchPath = List.of();

    public SimpleLocationHandler(final FileManager.Location location,
                                 final CompilerOption... supportedCompilerOptions) {
        super(location, supportedCompilerOptions);
    }

    @Override
    public Collection<Path> getPaths() {
        return searchPath;
    }

    @Override
    public void processOption(final CompilerOption compilerOption, final String value) {
        searchPath = value != null
                ? createPaths(value)
                : List.of();
    }

    private Collection<Path> createPaths(final String value) {
        if (value == null) {
            return List.of();
        }

        return Arrays.stream(value.split(Pattern.quote(File.pathSeparator)))
                .map(Paths::get)
                .toList();
    }
}
