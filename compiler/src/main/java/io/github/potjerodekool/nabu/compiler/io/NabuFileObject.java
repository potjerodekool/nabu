package io.github.potjerodekool.nabu.compiler.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class NabuFileObject implements FileObject {

    private final Kind kind;
    private final Path path;

    public NabuFileObject(final Kind kind,
                          final Path path) {
        this.kind = kind;
        this.path = path;
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return Files.newInputStream(
                path,
                StandardOpenOption.READ
        );
    }

    @Override
    public String getFileName() {
        return path.toString();
    }

    @Override
    public Kind getKind() {
        return kind;
    }
}
