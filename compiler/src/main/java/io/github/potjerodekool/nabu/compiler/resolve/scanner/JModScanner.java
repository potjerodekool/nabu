package io.github.potjerodekool.nabu.compiler.resolve.scanner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class JModScanner implements FileScanner, FileVisitor<Path> {

    private final Path path;
    private final StringBuilder currentPackage = new StringBuilder();
    private final Set<String> packageNames = new HashSet<>();
    private final FileSystem fileSystem;

    public JModScanner(final Path path) {
        this.path = path;
        try {
            this.fileSystem = getFileSystem();
            final var classes = fileSystem.getPath("/classes");
            Files.walkFileTree(classes, this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    private FileSystem getFileSystem() throws IOException {
        final var zipFile = URI.create("jar:file:" + path.toUri().getRawPath());

        try {
            return FileSystems.getFileSystem(zipFile);
        } catch (final FileSystemNotFoundException e) {
            return FileSystems.newFileSystem(zipFile, Map.of(
                    "create", "false",
                    "encoding", "UTF-8"));
        }
    }

    @Override
    public SearchResult findFile(final String fileName) {
        final var packageEnd = fileName.lastIndexOf('/');

        if (packageEnd < 0) {
            return EmptyResult.INSTANCE;
        }

        final var packageName = fileName.substring(0, packageEnd);

        if (!doesPackageExists(packageName)) {
            return EmptyResult.INSTANCE;
        }

        final var subPathName = "/classes/" + fileName;

        final var path = fileSystem.getPath(subPathName);

        try {
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

    private boolean doesPackageExists(final String packageName) {
        return this.packageNames.contains(packageName);
    }

    @Override
    public boolean hasDirectory(final String directoryName) {
        return doesPackageExists(directoryName);
    }

    @Override
    public SearchResult findDirectory(final String directoryName) {
        if (!doesPackageExists(directoryName)) {
            return EmptyResult.INSTANCE;
        }

        final var subPathName = "/classes/" + directoryName;

        final var path = fileSystem.getPath(subPathName);
        return Files.exists(path)
                ? new FileMatchResult(path)
                : EmptyResult.INSTANCE;
    }

    @Override
    public void walkDirectory(final String directoryName,
                              final Consumer<Path> consumer) {
        if (!doesPackageExists(directoryName)) {
            return;
        }

        final var subPathName = "/classes/" + directoryName;

        try {
            final var path = fileSystem.getPath(subPathName);
            try (var directoryStream = Files.newDirectoryStream(path, "*.class")) {
                directoryStream.forEach(consumer);
            }
        } catch (final IOException ignored) {
        }
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir,
                                             final BasicFileAttributes attrs) {
        final var fileName = dir.getFileName().toString().replace('/', '.');

        if (!"classes".equals(fileName)) {
            if (!currentPackage.isEmpty()) {
                currentPackage.append("/");
            }
            currentPackage.append(fileName);
            this.packageNames.add(currentPackage.toString());
        }
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

