package io.github.potjerodekool.nabu.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Where Nabu is installed and what it is. The launcher passes {@code -Dnabu.home} (the directory with {@code bin},
 * {@code current} and {@code versions}) and {@code -Dnabu.version} (the version in {@code current}).
 *
 * @param home        the installation directory, null when not started through the launcher
 * @param version     the version that runs
 * @param platform    for example {@code linux-x86_64}
 * @param releasesUrl the GitHub releases of Nabu ({@code NABU_RELEASES_URL} or {@code -Dnabu.releases.url})
 */
record Settings(Path home, String version, String platform, String releasesUrl) {

    static Settings fromSystem() {
        final String home = System.getProperty("nabu.home");
        String releases = System.getProperty("nabu.releases.url");
        if (releases == null || releases.isBlank()) {
            releases = System.getenv("NABU_RELEASES_URL");
        }
        return new Settings(
                home != null && !home.isBlank() ? Path.of(home) : null,
                System.getProperty("nabu.version", "unknown"),
                Platform.current(),
                releases != null && !releases.isBlank() ? releases : Releases.DEFAULT_URL);
    }

    Path versions() {
        return home.resolve("versions");
    }

    Path versionDirectory(final String version) {
        return versions().resolve(version);
    }

    /** The native backend is in {@code lib} of the version: its jar carries the LLVM libraries of the platform. */
    boolean nativeInstalled(final String version) {
        final Path lib = versionDirectory(version).resolve("lib");
        if (!Files.isDirectory(lib)) {
            return false;
        }
        try (Stream<Path> files = Files.list(lib)) {
            return files.anyMatch(f -> f.getFileName().toString().startsWith("native-backend-"));
        } catch (final java.io.IOException e) {
            return false;
        }
    }
}
