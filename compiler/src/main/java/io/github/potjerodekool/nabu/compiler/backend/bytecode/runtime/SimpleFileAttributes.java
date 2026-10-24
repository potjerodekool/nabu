package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/** {@link BasicFileAttributes} of a file, read through {@code java.io.File}; used by the Files substitution. */
public final class SimpleFileAttributes implements BasicFileAttributes {

    private final long size;
    private final long lastModified;
    private final boolean directory;
    private final boolean regularFile;

    public SimpleFileAttributes(final long size, final long lastModified, final boolean directory,
                                final boolean regularFile) {
        this.size = size;
        this.lastModified = lastModified;
        this.directory = directory;
        this.regularFile = regularFile;
    }

    @Override
    public FileTime lastModifiedTime() {
        return FileTime.fromMillis(lastModified);
    }

    @Override
    public FileTime lastAccessTime() {
        return FileTime.fromMillis(lastModified);
    }

    @Override
    public FileTime creationTime() {
        return FileTime.fromMillis(lastModified);
    }

    @Override
    public boolean isRegularFile() {
        return regularFile;
    }

    @Override
    public boolean isDirectory() {
        return directory;
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return !directory && !regularFile;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public Object fileKey() {
        return null;
    }
}
