package io.github.potjerodekool.nabu.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Options {

    private final List<Path> sourceRoots = new ArrayList<>();

    private Path targetDirectory;

    private JavaVersion targetVersion = JavaVersion.V17;

    public List<Path> getSourceRoots() {
        return sourceRoots;
    }

    public Options sourceRoots(final List<Path> sourceRoots) {
        this.sourceRoots.addAll(sourceRoots);
        return this;
    }

    public Options sourceRoot(final Path sourceRoot) {
        this.sourceRoots.add(sourceRoot);
        return this;
    }

    public JavaVersion getTargetVersion() {
        return targetVersion;
    }

    public Options targetVersion(final JavaVersion version) {
        this.targetVersion = version;
        return this;
    }

    public Options targetDirectory(final Path targetDirectory) {
        this.targetDirectory = targetDirectory;
        return this;
    }

    public Path getTargetDirectory() {
        return targetDirectory;
    }

    public enum JavaVersion {
        V17(61),
        V18(62),
        V19(63),
        V20(64),
        V21(65),
        V22(66),
        V23(67);

        private final int value;

        JavaVersion(final int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
}