package io.github.potjerodekool.nabu.compiler.io;

import io.github.potjerodekool.nabu.compiler.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.util.Pair;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class NabuCFileManager implements FileManager {

    private final Locations locations = new Locations();
    private final Map<Path, FileSystem> openFileSystems = new HashMap<>();

    @Override
    public void processOptions(final CompilerOptions allOptions) {
        locations.processOptions(allOptions);
    }

    @Override
    public Location getLocationForModule(final Location location,
                                         final String moduleName) {
        check(location);
        if (moduleName == null) {
            return null;
        }

        Location locationToUse = location;

        if (location == StandardLocation.SOURCE_OUTPUT && getSourceOutDir() == null) {
            locationToUse = StandardLocation.CLASS_OUTPUT;
        }

        return locations.getLocationForModule(locationToUse, moduleName);
    }

    private Path getSourceOutDir() {
        return null;
    }

    @Override
    public List<FileObject> listFiles(final Path path,
                                      final boolean recursive,
                                      final FileObject.Kind... kinds) {
        final var list = new ArrayList<FileObject>();

        use(path.getFileSystem(), fileSystem -> {
            if (Files.isRegularFile(path)) {
                final var kind = FileObject.Kind.fromExtension(path.getFileName().toString());
                list.add(new NabuFileObject(kind, path));
            } else if (Files.isDirectory(path)) {
                collectFiles(
                        path,
                        recursive,
                        list,
                        kinds
                );
            }
        });

        return list;
    }

    private void collectFiles(final Path path,
                              final boolean recursive,
                              final List<FileObject> files,
                              final FileObject.Kind... kinds) {
        try {
            final var visitor = new DirectoryVisitor(
                    path,
                    recursive,
                    files,
                    kinds
            );

            Files.walkFileTree(path, visitor);
        } catch (final IOException ignored) {
        }
    }

    public List<FileObject> getFilesForLocation(final Location location,
                                                final FileObject.Kind... kinds) {
        final var files = new ArrayList<FileObject>();
        final var paths = locations.getLocation(location);

        for (final var path : paths) {
            collectFiles(
                    path,
                    true,
                    files,
                    kinds
            );
        }

        return files;
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

    @Override
    public Iterable<Set<FileManager.Location>> listLocationsForModules(final StandardLocation location) {
        if (check(location)) {
            return locations.listLocationsForModules(location);
        } else {
            return List.of();
        }
    }

    @Override
    public String resolveModuleName(final Location location) {
        return locations.resolveModuleName(location);
    }

    @Override
    public FileObject getFileObject(final Location location, final String s, final ElementKind kind) {
        final Iterable<? extends Path> paths = getLocationAsPaths(location);

        for (final var path : paths) {
            final var subPath = path.resolve(s + ".class");
            if (Files.exists(subPath)) {
                return new NabuFileObject(
                        FileObject.Kind.CLASS,
                        subPath
                );
            }
        }

        return null;
    }

    @Override
    public Iterable<? extends FileObject> list(final Location location,
                                               final String packageName,
                                               final EnumSet<FileObject.Kind> kinds) {
        final var handler = locations.getLocationHandler(location);

        if (handler == null) {
            return List.of();
        } else {
            final var innerPackageName = packageName.replace('.', '/');

            final var result = new ArrayList<FileObject>();
            final var paths = handler.getPaths();

            paths.forEach(path -> {
                if (Files.isDirectory(path)) {
                    final var subPath = path.resolve(innerPackageName);

                    if (Files.exists(subPath)) {
                        result.addAll(listFilesOf(subPath, kinds));
                    }
                } else if (Files.isRegularFile(path)) {
                    final var zipFileSystem = findOrCreateFileSystem(path);
                    final var subPath = zipFileSystem.getPath(innerPackageName);
                    if (Files.exists(subPath)) {
                        result.addAll(listFilesOf(subPath, kinds));
                    }
                }
            });

            return result;
        }
    }

    private FileSystem findOrCreateFileSystem(final Path path) {
        return openFileSystems.computeIfAbsent(path, k -> {
            try {
                return FileSystems.newFileSystem(path
                );
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<NabuFileObject> listFilesOf(final Path packagePath,
                                             final EnumSet<FileObject.Kind> kinds) {
        try (final var stream = Files.list(packagePath)) {
            return stream
                    .map(file -> {
                        //Resolve file kind
                        final var fileName = file.getFileName().toString();
                        final var start = fileName.lastIndexOf('.');
                        final FileObject.Kind resolvedKind;
                        if (start > 0) {
                            final var fileExtension = fileName.substring(start);
                            resolvedKind = kinds.stream()
                                    .filter(it -> it.getExtension().equals(fileExtension))
                                    .findFirst()
                                    .orElse(null);
                        } else {
                            resolvedKind = null;
                        }
                        return new Pair<>(resolvedKind, file);
                    })
                    .filter(it -> it.first() != null)
                    .map(fileAndKind -> new NabuFileObject(fileAndKind.first(), fileAndKind.second()))
                    .toList();
        } catch (final IOException ignored) {
        }
        return null;
    }

    @Override
    public String resolveBinaryName(final Location location, final FileObject file) {
        final var fileName = file.getFileName();
        return StreamSupport.stream(locations.getLocationAsPaths(location).spliterator(), false)
                .map(path -> {
                    try {
                        return path.relativize(path.resolve(fileName));
                    } catch (final IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .map(relativizedPath -> {
                            // Replace path separators with dots and remove the file extension.
                            // So "com/example/Some.class" becomes "com.example.Some".

                            final var separator = relativizedPath.getFileSystem().getSeparator();
                            var name = relativizedPath
                                    .toString().replace(separator, ".");
                            return name.substring(0, name.lastIndexOf('.'));
                        }
                ).findFirst()
                .orElse(null);
    }

    private Iterable<? extends Path> getLocationAsPaths(final Location location) {
        return locations.getLocationAsPaths(location);
    }

    private boolean check(final Location location) {
        if (location == null) {
            return false;
        } else return location.isModuleOrientedLocation()
                || location.isOutputLocation();
    }

    @Override
    public void close() throws Exception {
        locations.close();
        openFileSystems.values().forEach(fs -> {
            try {
                fs.close();
            } catch (final IOException ignored) {
            }
        });
    }
}

class DirectoryVisitor implements FileVisitor<Path> {

    private final Path root;
    private final boolean recursive;
    private final Set<String> sourceExtensions;
    private final List<FileObject> sourceFiles;

    public DirectoryVisitor(final Path root,
                            final boolean recursive,
                            final List<FileObject> sourceFiles,
                            final FileObject.Kind... kinds) {
        this.root = root;
        this.recursive = recursive;
        this.sourceExtensions = Arrays.stream(kinds)
                .map(FileObject.Kind::getExtension)
                .collect(Collectors.toSet());
        this.sourceFiles = sourceFiles;
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
        return recursive || this.root.equals(dir)
                ? FileVisitResult.CONTINUE
                : FileVisitResult.TERMINATE;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
        final var fileExtension = getFileExtension(file);

        if (fileExtension != null && this.sourceExtensions.contains(fileExtension)) {
            sourceFiles.add(new NabuFileObject(FileObject.Kind.SOURCE, file));
        }
        return FileVisitResult.CONTINUE;
    }

    private String getFileExtension(final Path path) {
        final var fileName = path.getFileName().toString();
        final var start = fileName.lastIndexOf('.');
        return start > -1
                ? fileName.substring(start)
                : null;
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
