package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.lang.spi.LanguageParser;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.compiler.log.LoggerFactory;
import io.github.potjerodekool.nabu.util.Pair;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class NabuCFileManager implements FileManager {

    private final Logger logger = LoggerFactory.getLogger(NabuCFileManager.class.getName());

    private final Map<String, FileObject.Kind> extensionToKind = new HashMap<>();

    private final Set<FileObject.Kind> kindSet = new HashSet<>();

    private final Locations locations = new Locations();
    private final Map<Path, FileSystem> openFileSystems = new HashMap<>();

    public void initialize(final PluginRegistry pluginRegistry) {
        this.kindSet.addAll(
                pluginRegistry.getExtensionManager().getLanguageParsers().stream()
                        .map(LanguageParser::getSourceKind)
                        .collect(Collectors.toSet()));

        this.kindSet.add(FileObject.CLASS_KIND);
        this.kindSet.forEach(kind -> this.extensionToKind.put(kind.extension(), kind));
    }

    @Override
    public FileObject.Kind extensionToFileObjectKind(final String extension) {
        return this.extensionToKind.get(extension);
    }

    @Override
    public Set<FileObject.Kind> allKinds() {
        return new HashSet<>(kindSet);
    }

    public Set<FileObject.Kind> allSourceKinds() {
        return kindSet.stream()
                .filter(FileObject.Kind::isSource)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<FileObject.Kind> copyOf(final Set<FileObject.Kind> kinds) {
        return new HashSet<>(kinds);
    }

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
                final var kind = extensionToFileObjectKind(path.getFileName().toString());
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

    @Override
    public boolean hasLocation(final Location location) {
        return locations.hasLocation(location);
    }

    public void use(final FileSystem fileSystem,
                    final Consumer<FileSystem> fileSystemConsumer) {
        try {
            try (fileSystem) {
                fileSystemConsumer.accept(fileSystem);
            } catch (final IOException ignored) {
            }
        } catch (final UnsupportedOperationException e) {
            //Not all FileSystems support close()
            logger.log(LogLevel.DEBUG, "FileSystem doesn't support close()", e);
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
    public FileObject getFileObject(final Location location, final String s) {
        final Iterable<? extends Path> paths = getLocationAsPaths(location);

        for (final var path : paths) {
            final var subPath = path.resolve(s + ".class");
            if (Files.exists(subPath)) {
                return new NabuFileObject(
                        FileObject.CLASS_KIND,
                        subPath
                );
            }
        }

        return null;
    }

    @Override
    public Iterable<? extends FileObject> list(final Location location,
                                               final String packageName,
                                               final Set<FileObject.Kind> kinds) {
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
                return FileSystems.newFileSystem(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<NabuFileObject> listFilesOf(final Path packagePath,
                                             final Set<FileObject.Kind> kinds) {
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
                                    .filter(it -> it.extension().equals(fileExtension))
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
    public void close() {
        locations.close();
        openFileSystems.values().forEach(fs -> {
            try {
                fs.close();
            } catch (final IOException e) {
                logger.log(LogLevel.DEBUG, "Failed to close FileSystem during cleanup", e);
            }
        });
    }
}

class DirectoryVisitor implements FileVisitor<Path> {

    private final Path root;
    private final boolean recursive;
    private final Map<String, FileObject.Kind> extensionToKind;
    private final List<FileObject> sourceFiles;

    public DirectoryVisitor(final Path root,
                            final boolean recursive,
                            final List<FileObject> sourceFiles,
                            final FileObject.Kind... kinds) {
        this.root = root;
        this.recursive = recursive;
        this.extensionToKind = Arrays.stream(kinds)
                .collect(Collectors.toMap(
                        FileObject.Kind::extension,
                        Function.identity()
                ));

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

        if (fileExtension != null && this.extensionToKind.containsKey(fileExtension)) {
            final var kind = this.extensionToKind.get(fileExtension);
            sourceFiles.add(new NabuFileObject(kind, file));
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
