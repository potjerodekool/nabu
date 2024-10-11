package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.resolve.scanner.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public class ClassPath implements AutoCloseable {

    private final List<FileScanner> scanners = new ArrayList<>();

    public ClassPath() {
        final var home = Paths.get(System.getProperty("java.home"), "jmods");
        try {
            init(home);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        scanners.forEach(scanner -> {
            try {
                scanner.close();
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void init(final Path path) throws IOException {
        initScanners(path);
    }

    private void initScanners(final Path path) throws IOException {
        final var jmodMatcher = path.getFileSystem().getPathMatcher("glob:*.jmod");

        try (var dirStream = Files.newDirectoryStream(path)) {
            dirStream.forEach(subPath -> {
                if (jmodMatcher.matches(subPath.getFileName())) {
                    scanners.add(new JModScanner(subPath));
                }
            });
        }
    }

    public void loadJavaLang(final Consumer<FileMatchResult> consumer) {
        scanners.stream()
                .filter(scanner -> scanner.hasDirectory("java/lang"))
                .findFirst()
                .ifPresent(scanner -> scanner.walkDirectory("java/lang", path -> {
                    final var fileName = path.toString();

                    if (!fileName.contains("$")) {
                        try {
                            final var data = Files.readAllBytes(path);
                            consumer.accept(new FileMatchResult(data, path));
                        } catch (final IOException ignored) {
                        }
                    }
                }));
    }

    public Optional<FileMatchResult> find(final String className) {
        final var fileName = className.replace('.', '/') + ".class";
        return scanners.stream()
                .map(scanner -> scanner.findFile(fileName))
                .filter(searchResult -> searchResult instanceof FileMatchResult)
                .map(it -> (FileMatchResult) it)
                .findFirst();
    }

    public void addClassPathEntry(final Path path) {
        if (Files.isDirectory(path)) {
            scanners.add(new DirScanner(path));
        } else if (Files.isRegularFile(path)) {
            final var fileName = path.getFileName().toString();

            if (fileName.endsWith(".jar")) {
                scanners.add(new JarScanner(path));
            }
        }
    }
}


