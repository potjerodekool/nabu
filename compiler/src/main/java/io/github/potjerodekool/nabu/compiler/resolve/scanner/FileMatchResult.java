package io.github.potjerodekool.nabu.compiler.resolve.scanner;

import java.nio.file.Path;

public record FileMatchResult(byte[] data,
                              Path path) implements SearchResult {

    private static final byte[] NO_DATA = new byte[0];

    public FileMatchResult(final Path path) {
        this(NO_DATA, path);
    }

}
