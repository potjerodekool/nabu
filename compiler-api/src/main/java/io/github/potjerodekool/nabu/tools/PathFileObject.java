package io.github.potjerodekool.nabu.tools;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class PathFileObject implements FileObject {
    private final Kind kind;
    private final Path path;

    public PathFileObject(final Kind kind,
                          final Path path) {
        this.kind = kind;
        this.path = path;
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        createDirectory();
        return Files.newOutputStream(path);
    }

    private void createDirectory() throws IOException {
        final var parent = path.getParent();

        if (!Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    @Override
    public Reader openReader(final boolean ignoreEncodingErrors) throws IOException {
        return Files.newBufferedReader(path);
    }

    @Override
    public String getFileName() {
        return path.toString();
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public Writer openWriter() throws IOException {
        createDirectory();
        return Files.newBufferedWriter(path);
    }

    @Override
    public long getLastModified() {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException e) {
            return 0L;
        }
    }

    @Override
    public boolean delete() {
        try {
            Files.delete(path);
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
