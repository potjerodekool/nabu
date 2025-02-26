package io.github.potjerodekool.nabu.compiler;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class FileManager {

    public List<FileObject> listFiles(final Path path) {
        final var list = new ArrayList<FileObject>();

        use(path.getFileSystem(), fileSystem -> {
            if (Files.isRegularFile(path)) {
                list.add(new NabuFileObject(path));
            } else if (Files.isDirectory(path)) {
                final var visitor = new DirectoryVisitor(list);
                try {
                    Files.walkFileTree(path, visitor);
                } catch (final IOException ignored) {
                }
            }
        });

        return list;
    }

    public void use(final FileSystem fileSystem,
                    final Consumer<FileSystem> fileSystemConsumer) {
        try {
            try (fileSystem) {
                fileSystemConsumer.accept(fileSystem);
            } catch (final IOException ignored) {
            }
        } catch (final UnsupportedOperationException ignored) {
            //Can be thrown by FileSystem close method
        }
    }
}

class DirectoryVisitor implements FileVisitor<Path> {

    private final List<FileObject> sourceFiles;

    public DirectoryVisitor(final List<FileObject> sourceFiles) {
        this.sourceFiles = sourceFiles;
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
        if (file.toString().endsWith(".nabu")) {
            sourceFiles.add(new NabuFileObject(file));
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
        return FileVisitResult.TERMINATE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
        return FileVisitResult.CONTINUE;
    }
}

