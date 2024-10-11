package io.github.potjerodekool.nabu.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class NabuFileObject implements FileObject {

    private final Path path;

    public NabuFileObject(final Path path) {
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
        return path.getFileName().toString();
    }
}
