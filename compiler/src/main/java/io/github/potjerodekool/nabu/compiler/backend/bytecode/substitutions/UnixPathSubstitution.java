package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import java.io.File;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * {@code UnixPath.toRealPath} needs the native path functions of the JDK; here it resolves through
 * {@code java.io.File}. The class implements {@code Path} only so that {@code this} has the right type; the
 * body is copied into {@code sun.nio.fs.UnixPath} (see {@link JavaSubstitutions}).
 */
public abstract class UnixPathSubstitution implements Path {

    public Path toRealPath(final LinkOption... options) throws IOException {
        final File file = toFile();
        if (!file.exists()) {
            throw new NoSuchFileException(toString());
        }
        return file.getCanonicalFile().toPath();
    }
}
