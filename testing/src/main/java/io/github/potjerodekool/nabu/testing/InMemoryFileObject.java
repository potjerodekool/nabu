package io.github.potjerodekool.nabu.testing;

import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.TodoException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class InMemoryFileObject implements FileObject {

    private byte[] source;
    private final String fileName;

    public InMemoryFileObject(final String source,
                       final String fileName) {
        this(source.getBytes(StandardCharsets.UTF_8), fileName);
    }

    InMemoryFileObject(final byte[] source,
                       final String fileName) {
        this.source = source;
        this.fileName = fileName;
    }

    @Override
    public InputStream openInputStream() {
        return new ByteArrayInputStream(source);
    }

    @Override
    public OutputStream openOutputStream() {
        return new DelegateOutputStream(
                (data) -> this.source = data
        );
    }

    @Override
    public Reader openReader(final boolean ignoreEncodingErrors) {
        return new InputStreamReader(openInputStream());
    }

    @Override
    public String getFileName() {
        return fileName;
    }

    @Override
    public Kind getKind() {
        throw new TodoException();
    }

    @Override
    public Writer openWriter() {
        return new DelegateWriter(
                (data -> this.source = data
                ));
    }

    @Override
    public long getLastModified() {
        return 0;
    }

    @Override
    public boolean delete() {
        return false;
    }
}

class DelegateOutputStream extends OutputStream {

    private final ByteArrayOutputStream delegate;
    private final Consumer<byte[]> onClose;

    DelegateOutputStream(final Consumer<byte[]> onClose) {
        this.delegate = new ByteArrayOutputStream();
        this.onClose = onClose;
    }

    @Override
    public void write(final int b) {
        delegate.write(b);
    }

    @Override
    public void write(final byte[] b) throws IOException {
        delegate.write(b);
    }

    @Override
    public void write(final byte[] b, final int off, final int len) {
        delegate.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
        onClose.accept(delegate.toByteArray());
    }
}

class DelegateWriter extends Writer {

    private final ByteArrayOutputStream outputStream;
    private final OutputStreamWriter delegate;
    private final Consumer<byte[]> onClose;

    DelegateWriter(final Consumer<byte[]> onClose1) {
        this.outputStream = new ByteArrayOutputStream();
        this.delegate = new OutputStreamWriter(outputStream);
        this.onClose = onClose1;
    }

    @Override
    public void write(final char[] cbuf, final int off, final int len) throws IOException {
        delegate.write(cbuf, off, len);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
        onClose.accept(outputStream.toByteArray());
    }
}