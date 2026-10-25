package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

/** The {@link FileStore} of a file as {@code Files.getFileStore} reports it: the sizes come from {@code java.io.File}. */
public final class SimpleFileStore extends FileStore {

    private final File file;

    public SimpleFileStore(final File file) {
        this.file = file;
    }

    @Override
    public String name() {
        return file.getAbsoluteFile().toPath().getRoot().toString();
    }

    @Override
    public String type() {
        return "unknown";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public long getTotalSpace() throws IOException {
        return file.getTotalSpace();
    }

    @Override
    public long getUsableSpace() throws IOException {
        return file.getUsableSpace();
    }

    @Override
    public long getUnallocatedSpace() throws IOException {
        return file.getFreeSpace();
    }

    @Override
    public boolean supportsFileAttributeView(final Class<? extends FileAttributeView> type) {
        return false;
    }

    @Override
    public boolean supportsFileAttributeView(final String name) {
        return name.equals("basic") || name.equals("dos");
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(final Class<V> type) {
        return null;
    }

    @Override
    public Object getAttribute(final String attribute) throws IOException {
        if (attribute.equals("totalSpace")) {
            return Long.valueOf(file.getTotalSpace());
        } else if (attribute.equals("usableSpace")) {
            return Long.valueOf(file.getUsableSpace());
        } else if (attribute.equals("unallocatedSpace")) {
            return Long.valueOf(file.getFreeSpace());
        }
        throw new UnsupportedOperationException(attribute.concat(" not recognized"));
    }

    @Override
    public String toString() {
        return name();
    }
}
