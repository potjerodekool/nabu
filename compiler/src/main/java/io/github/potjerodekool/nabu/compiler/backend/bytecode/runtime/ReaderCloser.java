package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;

/** The close handler of a stream of lines: closes the reader the stream reads from. Not a lambda, see {@code FilesSubstitution}. */
public final class ReaderCloser implements Runnable {

    private final Closeable reader;

    public ReaderCloser(final Closeable reader) {
        this.reader = reader;
    }

    @Override
    public void run() {
        try {
            reader.close();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
