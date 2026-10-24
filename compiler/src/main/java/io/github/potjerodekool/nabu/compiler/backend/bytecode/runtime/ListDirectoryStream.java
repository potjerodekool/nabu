package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

/** The entries of a directory, read in advance; what {@code Files.newDirectoryStream} returns in a native executable. */
public final class ListDirectoryStream implements DirectoryStream<Path> {

    private final List<Path> entries;

    public ListDirectoryStream(final List<Path> entries) {
        this.entries = entries;
    }

    @Override
    public Iterator<Path> iterator() {
        return entries.iterator();
    }

    @Override
    public void close() throws IOException {
    }
}
