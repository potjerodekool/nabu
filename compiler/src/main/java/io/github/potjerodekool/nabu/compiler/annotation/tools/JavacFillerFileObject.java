package io.github.potjerodekool.nabu.compiler.annotation.tools;

import io.github.potjerodekool.nabu.tools.FileObject;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.JavaFileObject;
import java.io.*;
import java.net.URI;
import java.util.function.Consumer;

public class JavacFillerFileObject implements JavaFileObject {

    private final FileObject fileObject;
    private final Consumer<String> onCloseCallback;

    public JavacFillerFileObject(final FileObject fileObject,
                                 final Consumer<String> onCloseCallback) {
        this.fileObject = fileObject;
        this.onCloseCallback = onCloseCallback;
    }

    @Override
    public Kind getKind() {
        final var nabuKind = fileObject.getKind();
        if (nabuKind.isSource()) {
            return Kind.SOURCE;
        } else {
            return Kind.CLASS;
        }
    }

    @Override
    public boolean isNameCompatible(final String simpleName, final Kind kind) {
        final var fileName = fileObject.getFileName();
        return fileName != null && fileName.contains(simpleName);
    }

    @Override
    public NestingKind getNestingKind() {
        return null;
    }

    @Override
    public Modifier getAccessLevel() {
        return null;
    }

    @Override
    public URI toUri() {
        final var fileName = fileObject.getFileName();
        if (fileName != null) {
            return URI.create("file:///" + fileName);
        }
        return URI.create("string:///generated");
    }

    @Override
    public String getName() {
        return fileObject.getFileName();
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return fileObject.openInputStream();
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        return new FilerOutputStream(fileObject, onCloseCallback);
    }

    @Override
    public Reader openReader(final boolean ignoreEncodingErrors) throws IOException {
        return fileObject.openReader(ignoreEncodingErrors);
    }

    @Override
    public CharSequence getCharContent(final boolean ignoreEncodingErrors) throws IOException {
        try (final var reader = fileObject.openReader(ignoreEncodingErrors)) {
            final var sb = new StringBuilder();
            final var buffer = new char[4096];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
            return sb.toString();
        }
    }

    @Override
    public Writer openWriter() throws IOException {
        return new FilerWriter(fileObject, onCloseCallback);
    }

    @Override
    public long getLastModified() {
        return fileObject.getLastModified();
    }

    @Override
    public boolean delete() {
        return fileObject.delete();
    }
}

class FilerOutputStream extends FilterOutputStream {

    private final FileObject fileObject;
    private final Consumer<String> onCloseCallback;

    public FilerOutputStream(final FileObject fileObject,
                             final Consumer<String> onCloseCallback) throws IOException {
        super(fileObject.openOutputStream());
        this.fileObject = fileObject;
        this.onCloseCallback = onCloseCallback;
    }

    @Override
    public void close() throws IOException {
        super.close();
        onCloseCallback.accept(fileObject.getFileName());
    }
}

class FilerWriter extends FilterWriter {

    private final FileObject fileObject;
    private final Consumer<String> onCloseCallback;

    protected FilerWriter(final FileObject fileObject,
                          final Consumer<String> onCloseCallback) throws IOException {
        super(fileObject.openWriter());
        this.fileObject = fileObject;
        this.onCloseCallback = onCloseCallback;
    }

    @Override
    public void close() throws IOException {
        super.close();
        onCloseCallback.accept(fileObject.getFileName());
    }
}
