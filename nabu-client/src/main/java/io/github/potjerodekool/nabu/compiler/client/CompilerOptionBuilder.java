package io.github.potjerodekool.nabu.compiler.client;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CompilerOptionBuilder {

    private final List<String> sourcePath = new ArrayList<>();
    private final List<String> classPath = new ArrayList<>();
    private String outputDirectory;

    public CompilerOptionBuilder sourcePath(final String sourcePathElement) {
        this.sourcePath.add(sourcePathElement);
        return this;
    }

    public CompilerOptionBuilder sourcePath(final List<String> sourcePathElements) {
        this.sourcePath.addAll(sourcePathElements);
        return this;
    }

    public CompilerOptionBuilder classPath(final String classPathElement) {
        this.classPath.add(classPathElement);
        return this;
    }

    public CompilerOptionBuilder classPath(final List<String> classPathElements) {
        this.classPath.addAll(classPathElements);
        return this;
    }

    public CompilerOptionBuilder output(final String outputDirectory) {
        this.outputDirectory = outputDirectory;
        return this;
    }

    public Map<String, String> build() {
        final var optionsMap = new HashMap<String, String>();

        if (!classPath.isEmpty()) {
            optionsMap.put("--source-path", String.join(File.pathSeparator, sourcePath));
        }

        if (!classPath.isEmpty()) {
            optionsMap.put("--class-path", String.join(File.pathSeparator, classPath));
        }

        if (outputDirectory != null) {
            optionsMap.put("-d", outputDirectory);
        }

        return optionsMap;
    }
}
