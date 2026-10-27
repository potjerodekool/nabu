package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The object files of the C runtime ({@code nabu_runtime.c}, {@code jcl_lite.c}), compiled once and kept: every
 * program is linked with the same runtime, and compiling its 15000 lines again for each link costs seconds.
 *
 * <p>The files are kept in {@code ~/.nabu/cache/runtime/<key>/} (the system property {@code nabu.cache.dir} or the
 * environment variable {@code NABU_CACHE_DIR} name another directory). The key is a hash of the contents of the
 * runtime sources and headers, the compiler (its version line), the target and the flags: a change of any of them
 * makes a new entry. {@code -Dnabu.runtime.cache=false} switches the cache off.
 */
final class RuntimeObjects {

    private static final Map<String, List<Path>> KNOWN = new ConcurrentHashMap<>();

    private RuntimeObjects() {
    }

    /**
     * The compiled runtime for these sources, or null when it cannot be had (the caller then compiles the sources
     * in the link command, as before).
     *
     * @param compiler the C compiler
     * @param flags    the flags of the compilation, without {@code -c} and the file names
     */
    static List<Path> get(final List<Path> sources, final String compiler, final List<String> flags,
                          final String target) {
        if ("false".equalsIgnoreCase(System.getProperty("nabu.runtime.cache")) || sources.isEmpty()) {
            return null;
        }
        try {
            final String key = key(sources, compiler, flags, target);
            final List<Path> known = KNOWN.get(key);
            if (known != null && known.stream().allMatch(Files::isRegularFile)) {
                return known;
            }
            final Path dir = cacheDirectory().resolve(key);
            Files.createDirectories(dir);
            final List<Path> objects = new ArrayList<>();
            final List<Process> running = new ArrayList<>();
            final List<Path> temporary = new ArrayList<>();
            final List<Path> missing = new ArrayList<>();
            for (final Path source : sources) {
                final String name = source.getFileName().toString();
                final Path object = dir.resolve(name.substring(0, name.lastIndexOf('.')) + ".o");
                objects.add(object);
                if (!Files.isRegularFile(object)) {
                    missing.add(source);
                    final Path temp = dir.resolve(object.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
                    temporary.add(temp);
                    final List<String> cmd = new ArrayList<>();
                    cmd.add(compiler);
                    cmd.addAll(flags);
                    cmd.add("-c");
                    cmd.add(source.toString());
                    cmd.add("-o");
                    cmd.add(temp.toString());
                    running.add(new ProcessBuilder(cmd).redirectErrorStream(true).start());
                }
            }
            boolean ok = true;
            for (int i = 0; i < running.size(); i++) {
                final Process process = running.get(i);
                process.getInputStream().readAllBytes();
                if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) {
                    ok = false;
                }
            }
            for (int i = 0; i < temporary.size(); i++) {
                final Path temp = temporary.get(i);
                if (ok && Files.isRegularFile(temp)) {
                    final String name = temp.getFileName().toString();
                    final Path object = dir.resolve(name.substring(0, name.indexOf(".o.") + 2));
                    Files.move(temp, object, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(temp);
                }
            }
            if (!ok) {
                return null;
            }
            KNOWN.put(key, List.copyOf(objects));
            return List.copyOf(objects);
        } catch (final IOException | RuntimeException e) {
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static Path cacheDirectory() {
        String configured = System.getProperty("nabu.cache.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("NABU_CACHE_DIR");
        }
        final Path base = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".nabu", "cache");
        return base.resolve("runtime");
    }

    private static String key(final List<Path> sources, final String compiler, final List<String> flags,
                              final String target) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "target=" + target);
            update(digest, "flags=" + String.join(" ", flags));
            update(digest, "compiler=" + version(compiler));
            for (final Path source : sources) {
                update(digest, source.getFileName().toString());
                digest.update(Files.readAllBytes(source));
                // the headers next to it: a change in one changes the objects
                try (var siblings = Files.list(source.toAbsolutePath().getParent())) {
                    for (final Path header : siblings.filter(p -> p.getFileName().toString().endsWith(".h"))
                            .sorted().toList()) {
                        update(digest, header.getFileName().toString());
                        digest.update(Files.readAllBytes(header));
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (final NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void update(final MessageDigest digest, final String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static final Map<String, String> VERSIONS = new ConcurrentHashMap<>();

    /** The first line of {@code compiler --version}, with the path: another compiler or version is another key. */
    private static String version(final String compiler) throws IOException {
        final String known = VERSIONS.get(compiler);
        if (known != null) {
            return known;
        }
        String line = "";
        try {
            final Process process = new ProcessBuilder(compiler, "--version").redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(30, TimeUnit.SECONDS);
            line = output.lines().findFirst().orElse("");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final String result = compiler + " " + line;
        VERSIONS.put(compiler, result);
        return result;
    }
}
