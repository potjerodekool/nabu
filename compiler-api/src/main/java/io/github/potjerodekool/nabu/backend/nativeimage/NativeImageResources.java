package io.github.potjerodekool.nabu.backend.nativeimage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The resources a native executable carries, selected like
 * {@code native-image} does from the {@code resource-config.json} (and
 * {@code reachability-metadata.json}) on the class path: every file of a
 * class-path root (a directory or a jar) whose path matches an include
 * pattern and no exclude pattern. A resource bundle {@code a.b.Messages}
 * includes its {@code a/b/Messages*.properties} files; its class-based
 * variants ({@code a.b.Messages_nl}) are instantiated reflectively and are
 * returned as {@link #bundleClasses()}.
 *
 * <p>With the same path in several roots, the first root wins (class-path
 * order).
 */
public final class NativeImageResources {

    private final Map<String, byte[]> resources;
    private final List<String> bundleClasses;
    private final List<String> bundleNames;

    private NativeImageResources(final Map<String, byte[]> resources, final List<String> bundleClasses,
                                 final List<String> bundleNames) {
        this.resources = resources;
        this.bundleClasses = bundleClasses;
        this.bundleNames = bundleNames;
    }

    /**
     * Whether {@code binaryName} is a class of a configured bundle (its base
     * name, or that plus {@code _}locale): for classes compiled from source,
     * which the class-path scan does not see.
     */
    public boolean isBundleClass(final String binaryName) {
        for (final String bundle : bundleNames) {
            if (binaryName.equals(bundle) || (binaryName.startsWith(bundle + "_")
                    && binaryName.indexOf('.', bundle.length()) < 0)) {
                return true;
            }
        }
        return false;
    }

    /** Resource path (no leading slash) → contents, sorted by path. */
    public Map<String, byte[]> resources() {
        return resources;
    }

    /** Binary names of the classes of class-based resource bundles. */
    public List<String> bundleClasses() {
        return bundleClasses;
    }

    public static NativeImageResources collect(final List<GraalVMNativeImageConfig> configs,
                                               final List<Path> classPathRoots) {
        final List<Pattern> includes = new ArrayList<>();
        final List<Pattern> excludes = new ArrayList<>();
        final List<Pattern> bundleClassPatterns = new ArrayList<>();
        final List<String> bundleNames = new ArrayList<>();
        for (final var config : configs) {
            final var resourceConfig = config.resources();
            if (resourceConfig == null) {
                continue;
            }
            for (final var pattern : resourceConfig.resources()) {
                includes.add(Pattern.compile(pattern.pattern()));
            }
            for (final var pattern : resourceConfig.excludes()) {
                excludes.add(Pattern.compile(pattern.pattern()));
            }
            for (final var bundle : resourceConfig.bundles()) {
                bundleNames.add(bundle);
                final String base = Pattern.quote(bundle.replace('.', '/'));
                includes.add(Pattern.compile(base + "(_[^/]*)?\\.properties"));
                bundleClassPatterns.add(Pattern.compile(base + "(_[^/]*)?\\.class"));
            }
        }

        final Map<String, byte[]> resources = new TreeMap<>();
        final Set<String> bundleClasses = new LinkedHashSet<>();
        if (includes.isEmpty()) {
            return new NativeImageResources(resources, List.of(), List.of());
        }
        for (final Path root : classPathRoots) {
            if (root == null) {
                continue;
            }
            forEachFile(root, (name, file) -> {
                if (bundleClassPatterns.stream().anyMatch(p -> p.matcher(name).matches())) {
                    bundleClasses.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
                if (resources.containsKey(name)
                        || includes.stream().noneMatch(p -> p.matcher(name).matches())
                        || excludes.stream().anyMatch(p -> p.matcher(name).matches())) {
                    return;
                }
                try {
                    resources.put(name, Files.readAllBytes(file));
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return new NativeImageResources(resources, List.copyOf(bundleClasses), List.copyOf(bundleNames));
    }

    private interface FileVisitor {
        void visit(String name, Path file);
    }

    private static void forEachFile(final Path root, final FileVisitor visitor) {
        if (Files.isDirectory(root)) {
            walk(root, visitor);
        } else if (Files.isRegularFile(root) && root.getFileName().toString().endsWith(".jar")) {
            try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + root.toUri()), Map.of())) {
                walk(zip.getPath("/"), visitor);
            } catch (final IOException | RuntimeException e) {
                // An unreadable jar: skipped, as by NativeImageConfigScanner.
            }
        }
    }

    private static void walk(final Path root, final FileVisitor visitor) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                final var name = new StringBuilder();
                for (final Path part : root.relativize(file)) {
                    if (!name.isEmpty()) {
                        name.append('/');
                    }
                    name.append(part);
                }
                visitor.visit(name.toString(), file);
            });
        } catch (final IOException e) {
            // An unreadable root: skipped.
        }
    }
}
