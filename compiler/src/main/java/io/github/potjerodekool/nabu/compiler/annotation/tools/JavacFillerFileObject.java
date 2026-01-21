package io.github.potjerodekool.nabu.compiler.annotation.tools;

import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.TodoException;

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
        throw new TodoException();
    }

    @Override
    public boolean isNameCompatible(final String simpleName, final Kind kind) {
        throw new TodoException();
    }

    @Override
    public NestingKind getNestingKind() {
        return null;
    }

    @Override
    public Modifier getAccessLevel() {
        throw new TodoException();
    }

    @Override
    public URI toUri() {
        throw new TodoException();
    }

    @Override
    public String getName() {
        throw new TodoException();
    }

    @Override
    public InputStream openInputStream() throws IOException {
        throw new TodoException();
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
        throw new TodoException();
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
        this.onCloseCallback.accept(fileObject.getFileName());
    }
}