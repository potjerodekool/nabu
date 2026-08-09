package io.github.potjerodekool.nabu.compiler.daemon.launcher;

/**
 * Resolves the current JVM's os/arch to the classifier string used by
 * bytedeco's javacpp-presets artifacts (e.g. "linux-x86_64", "macosx-arm64").
 * <p>
 * The mapping logic is split out as [classifierFor] (pure function, no
 * System property reads) so it can be unit tested for every relevant
 * os.name/os.arch combination without needing to run on each platform.
 */
public final class PlatformDetector {

    private PlatformDetector() {
    }

    public static String classifier() {
        return classifierFor(
                System.getProperty("os.name"),
                System.getProperty("os.arch")
        );
    }

    public static String classifierFor(final String osName,
                                       final String osArch) {
        final var osPart = resolveOsPart(osName);
        final var archPart = resolveArchPart(osArch);
        return String.format("%s-%s", osPart, archPart);
    }

    private static String resolveOsPart(final String osName) {
        final var os = osName.toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return "macosx";
        } else if (os.contains("win")) {
            return "windows";
        } else if (os.contains("nux")) {
            return "linux";
        } else {
            throw new UnsupportedPlatformException(String.format("Unknown operating system: %s", osName));
        }
    }

    private static String resolveArchPart(final String osArch) {
        final var arch = osArch.toLowerCase();
        return switch (arch) {
            case "amd64", "x86_64", "x86-64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new UnsupportedPlatformException(String.format("Unknown architecture: %s", osArch));
        };
    }
}
