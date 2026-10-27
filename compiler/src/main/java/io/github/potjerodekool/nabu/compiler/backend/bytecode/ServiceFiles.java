package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The {@code META-INF/services/<service>} files of the class path: what {@code ServiceLoader} reads in a JVM. The files
 * become resources of the executable (read by {@code ServiceSupport}) and the providers they name are instantiated
 * through reflection, so they have to be compiled.
 *
 * @param files     the path of each file, with its contents per class-path entry, in order
 * @param providers the binary names of the providers
 */
public record ServiceFiles(Map<String, List<byte[]>> files, Set<String> providers) {

    private static final String PREFIX = "META-INF/services/";

    public static ServiceFiles scan(final List<Path> classPath) {
        final Map<String, List<byte[]>> files = new TreeMap<>();
        final Set<String> providers = new LinkedHashSet<>();
        for (final Path entry : classPath) {
            try {
                if (Files.isDirectory(entry)) {
                    scanDirectory(entry, files);
                } else if (Files.isRegularFile(entry)) {
                    scanJar(entry, files);
                }
            } catch (final IOException | RuntimeException e) {
                // an unreadable entry has no services for us
            }
        }
        for (final List<byte[]> contents : files.values()) {
            for (final byte[] data : contents) {
                providers.addAll(providerNames(data));
            }
        }
        return new ServiceFiles(files, providers);
    }

    /** The class names in a services file: one per line, {@code #} starts a comment. */
    static List<String> providerNames(final byte[] data) {
        final List<String> names = new ArrayList<>();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            final int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (!line.isEmpty()) {
                names.add(line);
            }
        }
        return names;
    }

    private static void scanDirectory(final Path root, final Map<String, List<byte[]>> files) throws IOException {
        final Path dir = root.resolve("META-INF/services");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            for (final Path file : list.filter(Files::isRegularFile).sorted().toList()) {
                files.computeIfAbsent(PREFIX + file.getFileName(), k -> new ArrayList<>())
                        .add(Files.readAllBytes(file));
            }
        }
    }

    private static void scanJar(final Path jar, final Map<String, List<byte[]>> files) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final var entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(PREFIX) && name.indexOf('/', PREFIX.length()) < 0) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        files.computeIfAbsent(name, k -> new ArrayList<>()).add(in.readAllBytes());
                    }
                }
            }
        }
    }
}
