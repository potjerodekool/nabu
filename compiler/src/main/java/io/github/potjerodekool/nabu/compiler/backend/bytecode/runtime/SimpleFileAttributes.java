package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * {@link DosFileAttributes} of a file, read through {@code java.io.File}; used by the Files substitution.
 * Read-only and hidden come from the file; archive and system are never set.
 */
public final class SimpleFileAttributes implements DosFileAttributes {

    private final long size;
    private final long lastModified;
    private final boolean directory;
    private final boolean regularFile;
    private boolean readOnly;
    private boolean hidden;

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

    public SimpleFileAttributes dos(final boolean readOnly, final boolean hidden) {
        this.readOnly = readOnly;
        this.hidden = hidden;
        return this;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public boolean isHidden() {
        return hidden;
    }

    @Override
    public boolean isArchive() {
        return false;
    }

    @Override
    public boolean isSystem() {
        return false;
    }
}
