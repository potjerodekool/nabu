package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.internal.Factory;
import io.github.potjerodekool.nabu.compiler.io.impl.*;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.*;
import io.github.potjerodekool.nabu.util.Pair;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public abstract class AbstractCompilerTest {

    private final CompilerContextImpl compilerContext = createCompilerContext();

    private PluginRegistry createPluginRegistry() {
        return new PluginRegistry();
    }

    protected Factory<ClassElementLoader> createElementLoader() {
        return TestClassElementLoader::new;
    }

    private CompilerContextImpl createCompilerContext() {
        final var fileManager = new TestFileManager();

        final var pluginRegistry = createPluginRegistry();
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, "src/test/resources")
                .build();
        fileManager.processOptions(options);

        final var context = new CompilerContextImpl(
                fileManager,
                pluginRegistry,
                createElementLoader()
        );

        return context;
    }

    protected CompilerContextImpl getCompilerContext() {
        return compilerContext;
    }

    public void addFakeClass(final String className) {
        final var loader = (TestClassElementLoader) getCompilerContext().getClassElementLoader();
        loader.addFakeClass(className);
    }
}

class TestFileManager implements CompilerFileManger {

    private final Set<Location> locations = new HashSet<>();
    private final Map<Location, LocationHandler> handlers = new HashMap<>();

    private final Set<FileObject.Kind> fileKinds = Set.of(new FileObject.Kind(".java", true));

    private final Map<Path, FileSystem> openFileSystems = new HashMap<>();

    @Override
    public void initialize(final PluginRegistry pluginRegistry) {
    }

    @Override
    public Location getLocationForModule(final Location location, final String moduleName) {
        throw new TodoException();
    }

    @Override
    public List<FileObject> listFiles(final Path path, final boolean recursive, final FileObject.Kind... kinds) {
        throw new TodoException();
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules(final StandardLocation location) {
        if (location == StandardLocation.MODULE_SOURCE_PATH) {
            return List.of(locations);
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public String resolveModuleName(final Location location) {
        if (location instanceof ModuleLocationHandler handler) {
            return handler.getModuleName();
        } else {
            return null;
        }
    }

    @Override
    public FileObject getFileObject(final Location location, final String s) {
        final var handler = getLocationHandler(location);

        if (handler == null) {
            return null;
        }

        final var paths = handler.getPaths();

        return null;
    }

    @Override
    public Iterable<? extends FileObject> list(final Location location,
                                               final String packageName,
                                               final Set<FileObject.Kind> kinds) {
        final LocationHandler handler = getLocationHandler(location);

        if (handler == null) {
            return Collections.emptyList();
        }

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
    public String resolveBinaryName(final Location location,
                                    final FileObject file) {
        final var fileName = file.getFileName();
        final LocationHandler handler = getLocationHandler(location);

        if (handler == null) {
            return null;
        }

        return handler.getPaths().stream()
                .map(path -> {
                    try {
                        return path.relativize(
                                path.resolve(fileName)
                        );
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

    @Override
    public FileObject.Kind extensionToFileObjectKind(final String extension) {
        throw new TodoException();
    }

    @Override
    public Set<FileObject.Kind> allKinds() {
        return fileKinds;
    }

    @Override
    public Set<FileObject.Kind> allSourceKinds() {
        throw new TodoException();
    }

    @Override
    public Set<FileObject.Kind> copyOf(final Set<FileObject.Kind> kinds) {
        return new HashSet<>(kinds);
    }

    @Override
    public void processOptions(final CompilerOptions compilerOptions) {
        final var option = compilerOptions.getOption(CompilerOption.SYSTEM).get();
        final var root = Paths.get(option, "jmods");

        try {
            final var moduleFinder = new ModuleFinder(root);
            Files.walkFileTree(root, moduleFinder);
            this.locations.addAll(moduleFinder.getLocations());
            this.handlers.putAll(moduleFinder.getHandlers());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<FileObject> getFilesForLocation(final Location location, final FileObject.Kind... kinds) {
        throw new TodoException();
    }

    @Override
    public boolean hasLocation(final Location location) {
        return location == StandardLocation.MODULE_SOURCE_PATH
                || location == StandardLocation.SOURCE_PATH;
    }

    private LocationHandler getLocationHandler(final Location location) {
        if (location instanceof LocationHandler locationHandler) {
            return locationHandler;
        } else {
            return this.handlers.get(location);
        }
    }

    @Override
    public void close() {
        openFileSystems.values().forEach(fileSystem -> {
            try {
                fileSystem.close();
            } catch (IOException ignored) {
            }
        });
    }
}

class ModuleFinder implements FileVisitor<Path> {

    private final Path root;
    private final Set<FileManager.Location> locations = new HashSet<>();
    private final Map<FileManager.Location, LocationHandler> handlers = new HashMap<>();

    public ModuleFinder(final Path root) {
        this.root = root;
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir,
                                             final BasicFileAttributes attrs) throws IOException {
        if (root.equals(dir)) {
            return FileVisitResult.CONTINUE;
        } else {
            if (dir.getParent().equals(root)) {
                final var moduleName = dir.getFileName().toString();
                final var handler = new ModuleLocationHandler("", moduleName, dir, false, true, false);
                locations.add(handler);
                this.handlers.put(StandardLocation.SOURCE_PATH, handler);
                this.handlers.put(StandardLocation.MODULE_SOURCE_PATH, handler);
            }

            return FileVisitResult.CONTINUE;
        }
    }

    @Override
    public FileVisitResult visitFile(final Path file,
                                     final BasicFileAttributes attrs) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file,
                                           final IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path dir,
                                              final IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    public Set<FileManager.Location> getLocations() {
        return locations;
    }

    public Map<FileManager.Location, LocationHandler> getHandlers() {
        return handlers;
    }
}