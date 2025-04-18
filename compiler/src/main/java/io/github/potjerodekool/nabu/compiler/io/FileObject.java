package io.github.potjerodekool.nabu.compiler.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public interface FileObject {

    InputStream openInputStream() throws IOException;

    String getFileName();

    Kind getKind();

    enum Kind {
        SOURCE_NABU(".nabu", true),
        SOURCE_JAVA(".java", true),
        CLASS(".class", false);

        private final String extension;
        private final boolean isSource;

        Kind(final String extension,
             final boolean isSource) {
            this.extension = extension;
            this.isSource = isSource;
        }

        public String getExtension() {
            return extension;
        }

        public boolean isSource() {
            return isSource;
        }

        public static Kind fromExtension(final String extension) {
            return Arrays.stream(values())
                    .filter(it -> it.getExtension().equals(extension))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown file extension: " + extension));
        }
    }
}
