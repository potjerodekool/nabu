package io.github.potjerodekool.nabu.compiler.resolve.scanner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class JarScanner implements FileScanner, FileVisitor<Path> {

    private final Path rootPath;
    private final StringBuilder currentPackage = new StringBuilder();
    private final Set<String> packageNames = new HashSet<>();
    private final FileSystem fileSystem;

    public JarScanner(final Path rootPath) {
        this.rootPath = rootPath;
        try {
            this.fileSystem = createFileSystem();
            final var classes = fileSystem.getPath("/");
            Files.walkFileTree(classes, this);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    private FileSystem createFileSystem() throws IOException {
        final var zipFile = URI.create("jar:file:" + rootPath.toUri().getRawPath());
        return FileSystems.newFileSystem(zipFile, Map.of(
                "create", "false",
                "encoding", "UTF-8"));
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

        final var subPathName = "/" + fileName;

        try {
            final var path = fileSystem.getPath(subPathName);
            if (Files.exists(path)) {
                final var bytecode = Files.readAllBytes(path);
                return new FileMatchResult(bytecode, path);
            } else {
                return EmptyResult.INSTANCE;
            }

        } catch (final IOException e) {
            return EmptyResult.INSTANCE;
        }
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir,
                                             final BasicFileAttributes attrs) {
        final var fileName = resolveFileName(dir);

        if (fileName != null) {
            if (!currentPackage.isEmpty()) {
                currentPackage.append("/");
            }
            currentPackage.append(fileName);
            this.packageNames.add(currentPackage.toString());
        }

        return FileVisitResult.CONTINUE;
    }

    private String resolveFileName(final Path dir) {
        var fileName = dir.getFileName();

        if (fileName == null) {
            return null;
        }

        return fileName.toString().replace('/', '.');
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
        final var fileName = resolveFileName(dir);

        if (fileName != null) {
            final var start = currentPackage.length() - fileName.length() - 1;
            if (start > -1) {
                currentPackage.delete(start, currentPackage.length());
            } else {
                currentPackage.delete(0, currentPackage.length());
            }
        }

        return FileVisitResult.CONTINUE;
    }
}
