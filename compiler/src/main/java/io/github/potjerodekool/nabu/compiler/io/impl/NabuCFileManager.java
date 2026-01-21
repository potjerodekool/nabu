package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.lang.spi.LanguageParser;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.tools.*;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import io.github.potjerodekool.nabu.util.Pair;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class NabuCFileManager implements FileManager {

    private final Logger logger = Logger.getLogger(NabuCFileManager.class.getName());

    private final Map<String, FileObject.Kind> extensionToKind = new HashMap<>();
    private final Set<FileObject.Kind> kindSet = new HashSet<>();
    private final Map<Location, BasicLocationHandler> handlersForLocation = new HashMap<>();
    private final Map<CompilerOption, BasicLocationHandler> handlerForOption = new HashMap<>();
    private final Map<Path, FileSystem> openFileSystems = new HashMap<>();

    public NabuCFileManager() {
        initLocationHandlers();
    }

    private void initLocationHandlers() {
        final List<BasicLocationHandler> locationHandlers = List.of(
                new ClassPathLocationHandler(),
                new SimpleLocationHandler(StandardLocation.SOURCE_PATH, CompilerOption.SOURCE_PATH),
                new SimpleLocationHandler(StandardLocation.ANNOTATION_PROCESSOR_PATH, CompilerOption.ANNOTATION_PROCESSOR_PATH),
                new SystemModulesLocationHandler(),
                new ModuleSourcePathLocationHandler(),
                new OutputLocationHandler(StandardLocation.CLASS_OUTPUT, CompilerOption.CLASS_OUTPUT),
                new OutputLocationHandler(StandardLocation.SOURCE_OUTPUT, CompilerOption.SOURCE_OUTPUT)
        );

        for (final var locationHandler : locationHandlers) {
            this.handlersForLocation.put(locationHandler.getLocation(), locationHandler);
        }

        for (final var locationHandler : locationHandlers) {
            for (final var supportedOption : locationHandler.getSupportedOptions()) {
                this.handlerForOption.put(
                        supportedOption,
                        locationHandler
                );
            }
        }
    }

    public void initialize(final PluginRegistry pluginRegistry) {
        this.kindSet.addAll(pluginRegistry.getLanguageSupportManager().getSourceKinds());
        this.kindSet.addAll(pluginRegistry.getLanguageParserManager().getSourceKinds());

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
    public void processOptions(final CompilerOptions options) {
        options.forEach((option, value) ->
                getHandlerForOption(option).ifPresent(handler -> handler.processOption(option, value)));
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

        return doGetLocationForModule(locationToUse, moduleName);
    }

    private Location doGetLocationForModule(final Location location,
                                                        final String moduleName) {
        final var handler = getLocationHandler(location);
        return handler != null
                ? handler.getLocationForModule(moduleName)
                : null;
    }

    public LocationHandler getLocationHandler(final Location location) {
        if (location == null) {
            return null;
        } else if (location instanceof LocationHandler locationHandler) {
            return locationHandler;
        } else {
            return handlersForLocation.get(location);
        }
    }


    private Path getSourceOutDir() {
        return getOutputLocation(StandardLocation.SOURCE_OUTPUT);
    }

    private Path getClassOutDir() {
        return getOutputLocation(StandardLocation.CLASS_OUTPUT);
    }

    private Path getOutputLocation(final Location location) {
        checkIfOutputLocation(location);
        final var handler = getLocationHandler(location);

        if (handler instanceof OutputLocationHandler outputLocationHandler) {
            return outputLocationHandler.getOutputDirectory();
        } else {
            return null;
        }
    }

    @Override
    public List<FileObject> listFiles(final Path path,
                                      final boolean recursive,
                                      final FileObject.Kind... kinds) {
        final var list = new ArrayList<FileObject>();

        use(path.getFileSystem(), fileSystem -> {
            if (Files.isRegularFile(path)) {
                final var kind = extensionToFileObjectKind(path.getFileName().toString());
                list.add(new PathFileObject(kind, path));
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
        final var paths = getLocationPaths(location);

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
    public FileObject getJavaFileForOutput(final Location location,
                                           final String className,
                                           final FileObject.Kind kind,
                                           final FileObject sibling) {
        checkIfOutputLocation(location);
        checkIfSourceOrClass(kind);
        return getFileForOutput(location, className, kind, sibling);
    }

    @Override
    public FileObject getFileForOutputForOriginatingFiles(final Location location,
                                                          final String packageName,
                                                          final String relativeName,
                                                          final FileObject... originatingFiles) {
        checkIfOutputLocation(location);
        final var separatorIndex = relativeName.indexOf('.');
        final var extension = relativeName.substring(separatorIndex);
        final var className = packageName + "." + relativeName.substring(0, separatorIndex);
        final var kind = new FileObject.Kind(extension, false);
        return getFileForOutput(location, className, kind, null);
    }

    private FileObject getFileForOutput(final Location location,
                                        final String className,
                                        final FileObject.Kind kind,
                                        final FileObject sibling) {
        Path dir = null;

        if (location == StandardLocation.SOURCE_OUTPUT) {
            dir = getSourceOutDir() != null
                    ? getSourceOutDir()
                    : getClassOutDir();
        } else if (location == StandardLocation.CLASS_OUTPUT) {
            dir = getClassOutDir();
        }

        if (dir == null) {
            dir = Paths.get(System.getProperty("user.dir"));
        }

        final var fileName = className.replace('.', '/')  + kind.extension();
        final var file = dir.resolve(fileName);
        return new PathFileObject(kind, file);
    }

    private void checkIfOutputLocation(final Location location) {
        if (!location.isOutputLocation()) {
            throw new IllegalArgumentException(String.format("Location %s isn't a output location ", location.getName()));
        }
    }

    private void checkIfSourceOrClass(final FileObject.Kind kind) {
        if (!kind.isSource() && !FileObject.CLASS_KIND.equals(kind)) {
            throw new IllegalArgumentException(String.format("kind is not source or class kind. Extension %s", kind.extension()));
        }
    }

    @Override
    public boolean hasLocation(final Location location) {
        return handlersForLocation.containsKey(location);
    }

    public Collection<Path> getLocationPaths(final Location location) {
        final var locationHandler = getLocationHandler(location);

        if (locationHandler == null) {
            return List.of();
        } else {
            return locationHandler.getPaths();
        }
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
    public Iterable<Set<Location>> listLocationsForModules(final StandardLocation location) {
        if (check(location)) {
            final var locationHandler = getLocationHandler(location);

            if (locationHandler == null) {
                return null;
            } else {
                return locationHandler.listLocationsForModules();
            }
        } else {
            return List.of();
        }
    }

    @Override
    public String resolveModuleName(final Location location) {
        final var handler = getLocationHandler(location);
        return handler != null
                ? handler.resolveModuleName()
                : null;
    }

    /*
     * TODO support other source extensions.
     */
    @Override
    public FileObject getFileObject(final Location location, final String className) {
        final Iterable<? extends Path> paths = getLocationAsPaths(location);

        for (final var path : paths) {
            final Path subPath;
            final FileObject.Kind kind;

            if (location.isClassLocation()) {
                subPath = path.resolve(className + ".class");
                kind = FileObject.CLASS_KIND;
            } else {
                subPath = path.resolve(className + ".java");
                kind = new FileObject.Kind(".java", true);
            }

            if (Files.exists(subPath)) {
                return new PathFileObject(
                        kind,
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
        final var handler = getLocationHandler(location);

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

    private List<PathFileObject> listFilesOf(final Path packagePath,
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
                    .map(fileAndKind -> new PathFileObject(fileAndKind.first(), fileAndKind.second()))
                    .toList();
        } catch (final IOException ignored) {
        }
        return null;
    }

    @Override
    public String resolveBinaryName(final Location location, final FileObject file) {
        final var fileName = file.getFileName();
        return StreamSupport.stream(getLocationAsPaths(location).spliterator(), false)
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
        final var handler = getLocationHandler(location);

        if (handler == null) {
            return List.of();
        } else {
            return handler.getPaths();
        }
    }

    private boolean check(final Location location) {
        if (location == null) {
            return false;
        } else return location.isModuleOrientedLocation()
                || location.isOutputLocation();
    }

    @Override
    public void close() {
        closeHandlers();
        openFileSystems.values().forEach(fs -> {
            try {
                fs.close();
            } catch (final IOException e) {
                logger.log(LogLevel.DEBUG, "Failed to close FileSystem during cleanup", e);
            }
        });
    }

    private void closeHandlers() {
        handlersForLocation.values().forEach(this::closeHandler);
    }

    private void closeHandler(final LocationHandler locationHandler) {
        if (locationHandler instanceof AutoCloseable autoCloseable) {
            try {
                autoCloseable.close();
            } catch (final Exception ignored) {
            }
        }
    }

    private Optional<BasicLocationHandler> getHandlerForOption(final CompilerOption compilerOption) {
        return Optional.ofNullable(this.handlerForOption.get(compilerOption));
    }

    public ClassLoader getClassLoader(final Location location) {
        final var handler = getLocationHandler(location);

        if (handler == null) {
            return null;
        }

        final var urls = handler.getPaths().stream()
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (final MalformedURLException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toArray(URL[]::new);
        return new URLClassLoader(urls);
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
            sourceFiles.add(new PathFileObject(kind, file));
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
