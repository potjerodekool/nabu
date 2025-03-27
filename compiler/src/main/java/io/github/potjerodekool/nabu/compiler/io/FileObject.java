package io.github.potjerodekool.nabu.compiler.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public interface FileObject {

    InputStream openInputStream() throws IOException;

    String getFileName();

    Kind getKind();

    enum Kind {
        SOURCE(".nabu"),
        SOURCE_JAVA(".java"),
        CLASS(".class");

        private final String extension;

        Kind(final String extension) {
            this.extension = extension;
        }

        public String getExtension() {
            return extension;
        }

        public static Kind fromExtension(final String extension) {
            return Arrays.stream(values())
                    .filter(it -> it.getExtension().equals(extension))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown file extension: " + extension));
        }
    }
}
