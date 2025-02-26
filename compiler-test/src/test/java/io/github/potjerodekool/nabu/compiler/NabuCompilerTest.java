package io.github.potjerodekool.nabu.compiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

class NabuCompilerTest {

    private final Path outputDir = Paths.get("output");

    @AfterEach
    void tearDown() throws IOException {
        delete(outputDir);
    }

    @BeforeEach
    void setup() throws IOException {
        if (!Files.exists(outputDir)) {
            Files.createDirectory(outputDir);
        }
    }

    private void delete(final Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.newDirectoryStream(path)) {
                stream.forEach(subPath -> {
                    try {
                        delete(subPath);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void test() throws IOException {
        try (final var compiler = new NabuCompiler()) {
            final var classPath = System.getProperty("java.class.path");
            final var pathSeparator = File.pathSeparator;
            final var pathElements = classPath.split(pathSeparator);

            for (final String pathElement : pathElements) {
                compiler.addClassPathEntry(Paths.get(pathElement));
            }

            final var options = new Options()
                    .sourceRoot(Paths.get("src/test/nabu"));

            compiler.compile(options);

            buildJar();
        }
    }

    private void buildJar() throws IOException {
        final var jarPath = Paths.get("lib","test-app.jar");
        final var parentPath = jarPath.getParent();

        if (Files.notExists(parentPath)) {
            Files.createDirectories(parentPath);
        }

        try (var outputStream = Files.newOutputStream(jarPath);
             var jarOutputStream = new JarOutputStream(outputStream)) {
            fillJar(jarOutputStream);
        }
    }

    private void fillJar(final JarOutputStream outputStream) throws IOException {
        Files.walkFileTree(outputDir, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                final var fileName = outputDir.relativize(dir);
                var name = fileName.toString().replace('\\', '/');

                if (!name.isEmpty()) {
                    if (!name.endsWith("/")) {
                        name = name + "/";
                    }

                    final var entry = new JarEntry(name);
                    entry.setTime(Files.getLastModifiedTime(dir).toMillis());
                    outputStream.putNextEntry(entry);
                    outputStream.closeEntry();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                final var fileName = outputDir.relativize(file);
                final var name = fileName.toString().replace('\\', '/');
                final var entry = new JarEntry(name);
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                outputStream.putNextEntry(entry);
                final var data = Files.readAllBytes(file);
                outputStream.write(data);
                outputStream.closeEntry();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

}