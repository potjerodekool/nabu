package io.github.potjerodekool.nabu.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code nabu self-update}: installs a newer version next to the one that runs and then points {@code current} at it.
 *
 * <p>The layout is {@code home/versions/<version>/} with the jars, and {@code home/current} with the version that the
 * launcher starts. Nothing of the running version is touched (on Windows its jars are locked), the switch is one
 * rename of {@code current}, and the previous version stays for going back ({@code --version}). A download that fails
 * or does not match {@code SHA256SUMS} leaves everything as it was: nothing is installed before everything that is
 * needed has been downloaded and checked.
 */
final class SelfUpdate {

    /** What the command line asks for. {@code nativeBackend} is null when the command line does not say. */
    record Options(boolean check, String version, Boolean nativeBackend) {
    }

    private static final List<String> NATIVE_LAUNCHERS = List.of("nabu-native-image", "nabu-native-image.cmd");

    private final Settings settings;
    private final Releases releases;
    private final PrintStream out;
    private final PrintStream err;

    SelfUpdate(final Settings settings, final Releases releases, final PrintStream out, final PrintStream err) {
        this.settings = settings;
        this.releases = releases;
        this.out = out;
        this.err = err;
    }

    /** @return the options, or null (after a message) when the arguments are not understood */
    static Options parse(final List<String> args, final PrintStream err) {
        boolean check = false;
        String version = null;
        Boolean nativeBackend = null;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--check" -> check = true;
                case "--native" -> nativeBackend = Boolean.TRUE;
                case "--no-native" -> nativeBackend = Boolean.FALSE;
                case "--version" -> {
                    if (i + 1 >= args.size()) {
                        err.println("nabu: --version needs a version");
                        return null;
                    }
                    version = args.get(++i);
                }
                default -> {
                    err.println("nabu: unknown option for self-update: " + args.get(i));
                    return null;
                }
            }
        }
        return new Options(check, version, nativeBackend);
    }

    static String usage() {
        return """
                Usage: nabu self-update [options]

                Installs the latest release (or another one) next to the running version and switches to it.

                  --check            only say whether a newer release exists
                  --version <v>      install this version (an older one too, to go back)
                  --native           also install the native backend for this platform
                  --no-native        do not carry the native backend over to the new version

                Without --native or --no-native the native backend is installed when the running version has it.
                """;
    }

    int run(final Options options) {
        if (settings.home() == null) {
            err.println("nabu: self-update needs an installation made by the install script (nabu.home is not set; "
                    + "start nabu through its launcher)");
            return 1;
        }
        try {
            return update(options);
        } catch (final IOException e) {
            err.println("nabu: self-update failed, nothing was changed: " + e.getMessage());
            return 1;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    private int update(final Options options) throws IOException, InterruptedException {
        final String running = settings.version();
        final String tag = options.version() != null ? "v" + Versions.strip(options.version()) : releases.latestTag();
        final String target = Versions.strip(tag);
        final boolean hasNative = settings.nativeInstalled(running);
        final boolean wantNative = options.nativeBackend() != null ? options.nativeBackend() : hasNative;

        final boolean newer = Versions.compare(target, running) > 0;
        final boolean other = !target.equals(running);
        final boolean addNative = !other && wantNative && !hasNative;
        if (options.version() == null && !newer && !addNative) {
            return upToDate(running, options);
        }
        if (options.check()) {
            out.println(other ? "nabu " + target + " is available (running " + running + "); "
                    + "update with: nabu self-update"
                    : "the native backend can be added with: nabu self-update --native");
            return 0;
        }

        final Path directory = settings.versionDirectory(target);
        final boolean haveCore = Files.isDirectory(directory) && Files.isDirectory(directory.resolve("lib"));
        final boolean haveNative = settings.nativeInstalled(target);
        final boolean needCore = !haveCore;
        final boolean needNative = wantNative && !haveNative;
        if (!needCore && !needNative && !other) {
            return upToDate(running, options);
        }

        final String coreFile = "nabu-" + target + ".zip";
        final String nativeFile = "nabu-native-" + target + "-" + settings.platform() + ".zip";

        out.println("nabu " + running + " -> " + target + (wantNative ? " (with the native backend)" : ""));
        Files.createDirectories(settings.home());
        final Path work = Files.createTempDirectory(settings.home(), "update-");
        try {
            // everything is downloaded and checked before anything is installed
            final Path sums = work.resolve("SHA256SUMS");
            releases.download(tag, "SHA256SUMS", sums, out);
            final Map<String, String> expected = Archives.parseSums(Archives.read(sums));
            final List<Path> archives = new ArrayList<>();
            if (needCore) {
                archives.add(fetch(tag, coreFile, work, expected));
            }
            if (needNative) {
                archives.add(fetch(tag, nativeFile, work, expected));
            }

            final Path staging = needCore ? settings.versions().resolve(target + ".partial") : directory;
            if (needCore) {
                deleteTree(staging);
            }
            for (final Path archive : archives) {
                Archives.extract(archive, staging);
            }
            if (needCore) {
                Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
            }
            if (other) {
                switchTo(target);
                prune(target, running);
            }
            syncNativeLaunchers(other ? target : running);
        } finally {
            deleteTree(work);
            deleteTree(settings.versions().resolve(target + ".partial"));
        }
        out.println("nabu " + target + " is installed" + (other ? " and current" : "")
                + (settings.nativeInstalled(target) ? ", with the native backend" : ""));
        return 0;
    }

    /** Nothing to install; the launchers of the native backend are put right all the same (an older update left them out). */
    private int upToDate(final String running, final Options options) {
        if (!options.check()) {
            syncNativeLaunchers(running);
        }
        out.println("nabu " + running + " is up to date");
        return 0;
    }

    private Path fetch(final String tag, final String file, final Path work, final Map<String, String> expected)
            throws IOException, InterruptedException {
        final String sum = expected.get(file);
        if (sum == null) {
            throw new IOException(file + " is not in SHA256SUMS of release " + tag
                    + " (is it published for this platform?)");
        }
        final Path target = work.resolve(file);
        releases.download(tag, file, target, out);
        final String actual = Archives.sha256(target);
        if (!actual.equals(sum)) {
            throw new IOException(file + " does not match SHA256SUMS (expected " + sum + ", got " + actual + ")");
        }
        return target;
    }

    private void switchTo(final String version) throws IOException {
        final Path current = settings.home().resolve("current");
        final Path next = settings.home().resolve("current.new");
        Files.writeString(next, version + System.lineSeparator());
        Files.move(next, current, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The launchers of the native backend ({@code nabu-native-image}) are in {@code bin} of the native archive. They
     * are in {@code <home>/bin} as long as the current version has the native backend, and gone when it has not.
     */
    private void syncNativeLaunchers(final String current) {
        final Path bin = settings.home().resolve("bin");
        try {
            for (final String name : NATIVE_LAUNCHERS) {
                final Path installed = bin.resolve(name);
                final Path shipped = settings.versionDirectory(current).resolve("bin").resolve(name);
                if (settings.nativeInstalled(current) && Files.isRegularFile(shipped)) {
                    Files.createDirectories(bin);
                    Files.copy(shipped, installed, StandardCopyOption.REPLACE_EXISTING);
                    installed.toFile().setExecutable(true);
                } else {
                    Files.deleteIfExists(installed);
                }
            }
        } catch (final IOException e) {
            err.println("nabu: could not update the launchers in " + bin + ": " + e.getMessage());
        }
    }

    /** Keeps the new version and the one that was current; older ones go (a version that is in use stays). */
    private void prune(final String keep, final String previous) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(settings.versions())) {
            for (final Path version : stream) {
                final String name = version.getFileName().toString();
                if (!name.equals(keep) && !name.equals(previous) && Files.isDirectory(version)) {
                    deleteTree(version);
                }
            }
        } catch (final IOException e) {
            err.println("nabu: could not remove old versions: " + e.getMessage());
        }
    }

    private static void deleteTree(final Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException ignored) {
                    // a locked file of a version that still runs: it stays
                }
            });
        } catch (final IOException ignored) {
            // the same
        }
    }
}
