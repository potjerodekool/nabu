package io.github.potjerodekool.nabu.compiler.incremental;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.FileObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent per-output-directory build state used to skip recompilation when
 * neither the sources nor the relevant compiler options changed.
 *
 * The state file is only committed after a fully successful compilation, so a
 * failed build is always recompiled from scratch on the next run.
 */
public final class IncrementalBuildState {

    private static final byte[] MAGIC = "NABINC-STATE".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;

    public static final String FILE_NAME = ".nabu-incremental-state";

    private final String configFingerprint;
    private final List<SourceEntry> sources;
    private final Map<String, SourceEntry> sourcesByPath;

    public record SourceEntry(String path, String hash, boolean generated, List<String> producedClasses) {
    }

    private IncrementalBuildState(final String configFingerprint,
                                  final List<SourceEntry> sources) {
        this.configFingerprint = configFingerprint;
        this.sources = List.copyOf(sources);
        final var byPath = new HashMap<String, SourceEntry>();
        for (final var source : this.sources) {
            byPath.put(source.path(), source);
        }
        this.sourcesByPath = Map.copyOf(byPath);
    }

    public static Path stateFile(final Path outputDirectory) {
        return outputDirectory.resolve(FILE_NAME);
    }

    /**
     * Normalizes a file name to an absolute, separator-normalized key that is
     * stable across runs.
     */
    public static String normalizePath(final String fileName) {
        final var normalized = Paths.get(fileName).toAbsolutePath().normalize().toString();
        return normalized.replace(File.separatorChar, '/');
    }

    public static String sha256Of(final byte[] content) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(content));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not supported", e);
        }
    }

    public static String sha256OfPath(final Path path) {
        try {
            return sha256Of(Files.readAllBytes(path));
        } catch (final IOException e) {
            return "";
        }
    }

    private static String toHex(final byte[] bytes) {
        final var hex = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Fingerprint of the compiler options that influence the generated output.
     * Changing any of them invalidates the incremental state.
     */
    public static String configFingerprint(final CompilerOptions options) {
        final var parts = new ArrayList<String>();
        parts.add("nabu-incremental-v" + VERSION);
        for (final var option : CompilerOption.INCREMENTAL_FINGERPRINT_OPTIONS) {
            parts.add(option.optionName());
            parts.add(options.getOption(option).orElse(""));
        }
        return sha256Of(String.join("#", parts).getBytes(StandardCharsets.UTF_8));
    }

    public static IncrementalBuildState read(final Path stateFile) {
        if (!Files.exists(stateFile)) {
            return null;
        }

        try (final var input = new DataInputStream(Files.newInputStream(stateFile))) {
            final var magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                return null;
            }
            if (input.readInt() != VERSION) {
                return null;
            }

            final var fingerprint = input.readUTF();

            final var sourceCount = input.readInt();
            final var sources = new ArrayList<SourceEntry>(sourceCount);
            for (var i = 0; i < sourceCount; i++) {
                final var path = input.readUTF();
                final var generated = input.readBoolean();
                final var hash = input.readUTF();

                final var classCount = input.readInt();
                final var producedClasses = new ArrayList<String>(classCount);
                for (var j = 0; j < classCount; j++) {
                    producedClasses.add(input.readUTF());
                }

                sources.add(new SourceEntry(path, hash, generated, List.copyOf(producedClasses)));
            }

            return new IncrementalBuildState(fingerprint, sources);
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    public static boolean isUpToDate(final Path stateFile,
                                     final CompilerOptions options,
                                     final List<? extends FileObject> currentSourceFiles) {
        final var state = read(stateFile);
        if (state == null) {
            return false;
        }
        if (!state.configFingerprint.equals(configFingerprint(options))) {
            return false;
        }

        final Map<String, String> current = new LinkedHashMap<>();
        for (final var sourceFile : currentSourceFiles) {
            final var path = normalizePath(sourceFile.getFileName());
            current.put(path, sha256OfPath(Paths.get(path)));
        }

        for (final var entry : state.sources) {
            final var currentHash = current.get(entry.path());
            if (entry.generated() && currentHash == null) {
                // Generated file outside the resolved source set: it must
                // still exist and be unchanged on disk (deleting or editing it
                // forces a recompile).
                final var path = Paths.get(entry.path());
                if (!Files.exists(path) || !entry.hash().equals(sha256OfPath(path))) {
                    return false;
                }
            } else {
                if (currentHash == null) {
                    return false;
                }
                if (!entry.hash().equals(currentHash)) {
                    return false;
                }
            }
        }

        for (final var path : current.keySet()) {
            if (!state.sourcesByPath.containsKey(path)) {
                return false;
            }
        }

        return true;
    }

    public static void write(final Path stateFile,
                             final String configFingerprint,
                             final Map<String, List<String>> producedBySource,
                             final List<? extends FileObject> originalSourceFiles) {
        final var entries = new ArrayList<SourceEntry>();
        final var seen = new HashSet<String>();

        for (final var sourceFile : originalSourceFiles) {
            final var path = normalizePath(sourceFile.getFileName());
            if (!seen.add(path)) {
                continue;
            }
            final var hash = sha256OfPath(Paths.get(path));
            entries.add(new SourceEntry(path, hash, false, producedBySource.getOrDefault(path, List.of())));
        }

        producedBySource.forEach((path, producedClasses) -> {
            if (seen.add(path)) {
                final var hash = sha256OfPath(Paths.get(path));
                entries.add(new SourceEntry(path, hash, true, producedClasses));
            }
        });

        entries.sort(Comparator.comparing(SourceEntry::path));
        write(stateFile, configFingerprint, List.copyOf(entries));
    }

    private static void write(final Path stateFile,
                              final String configFingerprint,
                              final List<SourceEntry> sources) {
        try {
            Files.createDirectories(stateFile.getParent());

            final var temp = stateFile.resolveSibling(stateFile.getFileName().toString() + ".tmp");
            try (final var output = new DataOutputStream(Files.newOutputStream(
                    temp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING))) {
                output.write(MAGIC);
                output.writeInt(VERSION);
                output.writeUTF(configFingerprint);

                output.writeInt(sources.size());
                for (final var source : sources) {
                    output.writeUTF(source.path());
                    output.writeBoolean(source.generated());
                    output.writeUTF(source.hash());

                    output.writeInt(source.producedClasses().size());
                    for (final var producedClass : source.producedClasses()) {
                        output.writeUTF(producedClass);
                    }
                }
                output.flush();
            }

            try {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            // State persistence is best-effort: a failed write only means the
            // next compile is performed from scratch.
        }
    }

    /**
     * Removes class files produced by a previous compilation that are no longer
     * produced by the current one (e.g. deleted or renamed sources).
     */
    public static void deleteStaleClasses(final Path outputDirectory,
                                          final IncrementalBuildState previousState,
                                          final Collection<List<String>> newProducedClasses) {
        if (previousState == null) {
            return;
        }

        final var stillProduced = new HashSet<String>();
        newProducedClasses.forEach(stillProduced::addAll);

        for (final var source : previousState.sources) {
            for (final var producedClass : source.producedClasses()) {
                if (stillProduced.contains(producedClass)) {
                    continue;
                }
                final var classFile = outputDirectory.resolve(producedClass);
                try {
                    Files.deleteIfExists(classFile);
                } catch (final IOException ignored) {
                }
            }
        }
    }
}