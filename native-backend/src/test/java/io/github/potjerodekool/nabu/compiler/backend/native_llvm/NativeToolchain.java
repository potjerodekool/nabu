package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Test-helpers voor de native toolchain.
 *
 * De tests linken en draaien echte native binaries en hebben daarvoor een
 * gcc/MinGW-toolchain nodig. Waar die staat verschilt per machine, dus de
 * tests hardcoden geen paden meer: ze gebruiken dezelfde resolutie als de
 * productiecode ({@link Linker#resolveCompiler(String)} /
 * {@link Linker#toolchainBin()}) en slaan zichzelf over als er geen
 * toolchain beschikbaar is.
 *
 * Configureer de toolchain zo nodig met {@code -Dnabu.gcc=<pad>},
 * {@code -Dnabu.toolchain.bin=<map>} of de env-vars {@code NABU_GCC} /
 * {@code NABU_TOOLCHAIN_BIN}.
 */
final class NativeToolchain {

    private NativeToolchain() {
    }

    /**
     * Slaat de test over wanneer er geen gcc beschikbaar is; anders het
     * absolute pad van gcc.
     */
    static String assumeGcc() {
        final var gcc = Linker.resolveCompiler("gcc");

        Assumptions.assumeTrue(
                !gcc.equals("gcc") && Files.isExecutable(Path.of(gcc)),
                "geen uitvoerbare gcc/MinGW-toolchain gevonden; zet -Dnabu.gcc=<pad> "
                        + "of -Dnabu.toolchain.bin=<map> (env: NABU_GCC / NABU_TOOLCHAIN_BIN)");

        return gcc;
    }

    /**
     * Whether a file in the output directory is the linked executable: {@code <name>.exe} on Windows, a file
     * without an extension that can be executed on Linux and macOS.
     */
    static boolean isExecutableOutput(final Path file) {
        final var name = file.getFileName().toString();
        return Files.isRegularFile(file)
                && (name.endsWith(".exe") || (!name.contains(".") && Files.isExecutable(file)));
    }

    /**
     * ProcessBuilder om een gelinkte exe te draaien, met de map van de
     * toolchain op de PATH zodat de runtime-DLL's (libgcc_s_seh-1.dll en
     * vrienden) gevonden worden.
     */
    static ProcessBuilder run(final Path executable) {
        final var pb = new ProcessBuilder(executable.toString());
        final var bin = runtimeBin();

        if (bin != null) {
            final var pathKey = pathKey(pb);
            pb.environment().compute(pathKey, (k, v) -> bin + File.pathSeparator + (v == null ? "" : v));
        }

        return pb;
    }

    /**
     * Map met de MinGW-runtime-DLL's: de map van de gevonden gcc, of de
     * geconfigureerde toolchain-map. {@code null} als gcc alleen als kale naam
     * bekend is (ProcessBuilder vindt de DLL's dan zelf).
     */
    static String runtimeBin() {
        final var gcc = Linker.resolveCompiler("gcc");

        if (!gcc.equals("gcc")) {
            final Path parent = Path.of(gcc).toAbsolutePath().getParent();
            if (parent != null) {
                return parent.toString();
            }
        }

        final var toolchainBin = Linker.toolchainBin();
        return toolchainBin != null ? toolchainBin.toString() : null;
    }

    /**
     * Bewaart een gegenereerd {@code .ll}/{@code .o}-artefact onder
     * {@code target/ir-dumps} zodat het inspecteerbaar blijft zonder dat er een
     * machine-specifiek pad in de test hoeft te staan.
     */
    static void dump(final Path artifact, final String name) {
        try {
            final var dumpDir = Path.of("target", "ir-dumps");
            Files.createDirectories(dumpDir);
            Files.copy(artifact, dumpDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new UncheckedIOException("Kan IR-dump niet schrijven: " + artifact, e);
        }
    }

    /** Windows gebruikt {@code Path}, POSIX-platformen {@code PATH}. */
    private static String pathKey(final ProcessBuilder pb) {
        for (final var key : pb.environment().keySet()) {
            if ("PATH".equalsIgnoreCase(key)) {
                return key;
            }
        }

        return "PATH";
    }
}
