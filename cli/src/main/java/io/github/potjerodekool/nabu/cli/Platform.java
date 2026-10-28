package io.github.potjerodekool.nabu.cli;

import java.util.Locale;

/** The name of the platform in the names of the release files: {@code linux-x86_64}, {@code windows-x86_64}, ... */
final class Platform {

    private Platform() {
    }

    static String current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String of(final String osName, final String osArch) {
        final String os = osName.toLowerCase(Locale.ROOT);
        final String arch = osArch.toLowerCase(Locale.ROOT);
        final String system = os.contains("win") ? "windows"
                : os.contains("mac") || os.contains("darwin") ? "macosx"
                : os.contains("linux") ? "linux"
                : os.replaceAll("[^a-z0-9]", "");
        final String cpu = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64") ? "x86_64"
                : arch.equals("aarch64") || arch.equals("arm64") ? "arm64"
                : arch.replaceAll("[^a-z0-9_]", "");
        return system + "-" + cpu;
    }
}
