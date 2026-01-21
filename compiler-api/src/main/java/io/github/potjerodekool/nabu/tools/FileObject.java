package io.github.potjerodekool.nabu.tools;

import java.io.*;

/**
 * Representation of a file.
 */
public interface FileObject {

    /**
     * Kind of Java class files.
     */
    FileObject.Kind CLASS_KIND = new FileObject.Kind(".class", false);

    /**
     * @return Returns an InputStream to read the content of the file.
     * @throws IOException Thrown if exception occurred when opening the file.
     * For example when the file is missing.
     */
    InputStream openInputStream() throws IOException;

    OutputStream openOutputStream() throws IOException;

    Reader openReader(boolean ignoreEncodingErrors) throws IOException;

    /**
     * @return Returns the fileName.
     */
    String getFileName();

    /**
     * @return Returns the file kind of this file.
     */
    Kind getKind();

    Writer openWriter() throws IOException;

    long getLastModified();

    boolean delete();

    /**
     * FileObject kind.
     *
     * @param extension The file extension including the dot. For example '.class'.
     * @param isSource If the kind is source or not.
     */
    record Kind(String extension,
                boolean isSource) {
    }

}
