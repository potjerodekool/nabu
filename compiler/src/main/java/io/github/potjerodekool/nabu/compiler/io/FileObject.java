package io.github.potjerodekool.nabu.compiler.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public interface FileObject {

    FileObject.Kind JAVA_KIND = new FileObject.Kind(".java", true);
    FileObject.Kind CLASS_KIND = new FileObject.Kind(".class", false);

    InputStream openInputStream() throws IOException;

    String getFileName();

    Kind getKind();

    record Kind(String extension,
                boolean isSource) {
    }

}
