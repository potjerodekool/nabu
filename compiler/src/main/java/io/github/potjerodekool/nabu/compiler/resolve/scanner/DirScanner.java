package io.github.potjerodekool.nabu.compiler.resolve.scanner;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

public class DirScanner implements FileScanner, FileVisitor<Path> {

    private final Path root;
    private final Set<String> packageNames = new HashSet<>();
    private final StringBuilder currentPackage = new StringBuilder();

    public DirScanner(final Path root) {
        this.root = root;
        try {
            Files.walkFileTree(root, this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
    }

    @Override
    public SearchResult findFile(final String fileName) {
        final var packageEnd = fileName.lastIndexOf('/');

        if (packageEnd < 0) {
            return EmptyResult.INSTANCE;
        }

        final var packageName = fileName.substring(0, packageEnd);
        final var packageExists = this.packageNames.contains(packageName);

        if (!packageExists) {
            return EmptyResult.INSTANCE;
        }

        final var path = root.resolve(fileName);
        if (Files.exists(path)) {
            try {
                final var bytecode = Files.readAllBytes(path);
                return new FileMatchResult(bytecode, path);
            } catch (final IOException e) {
                return EmptyResult.INSTANCE;
            }
        } else {
            return EmptyResult.INSTANCE;
        }
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
        if (dir.equals(root)) {
            return FileVisitResult.CONTINUE;
        }

        final var fileName = dir.getFileName().toString().replace('/', '.');

        if (!currentPackage.isEmpty()) {
            currentPackage.append("/");
        }
        currentPackage.append(fileName);
        this.packageNames.add(currentPackage.toString());
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
        return FileVisitResult.TERMINATE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
        final var fileName = dir.getFileName().toString().replace('/', '.');
        final var start = currentPackage.length() - fileName.length() - 1;
        if (start > -1) {
            currentPackage.delete(start, currentPackage.length());
        } else {
            currentPackage.delete(0, currentPackage.length());
        }
        return FileVisitResult.CONTINUE;
    }
}
