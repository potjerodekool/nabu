package io.github.potjerodekool.nabu.tools;

import java.io.IOException;
import java.io.InputStream;

public interface FileObject {

    FileObject.Kind CLASS_KIND = new FileObject.Kind(".class", false);

    InputStream openInputStream() throws IOException;

    String getFileName();

    Kind getKind();

    record Kind(String extension,
                boolean isSource) {
    }

}
