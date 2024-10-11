package io.github.potjerodekool.nabu.compiler;

import java.io.IOException;
import java.io.InputStream;

public interface FileObject {

    InputStream openInputStream() throws IOException;

    String getFileName();
}
