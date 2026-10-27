package io.github.potjerodekool.nabu.nativeimage;

import io.github.potjerodekool.nabu.compiler.backend.native_llvm.Linker;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** {@code nabu-native-image --doctor}: checks what a build needs and says what is missing. */
final class Doctor {

    private Doctor() {
    }

    /** @return 0 when a build can work, 1 when something that it needs is missing */
    static int run(final PrintStream out) {
        boolean ok = true;
        out.println("nabu-native-image " + NativeImage.version());
        out.println("  java      " + System.getProperty("java.version") + " (" + System.getProperty("java.home") + ")");
        out.println("  system    " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        final boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        final String tool = mac ? "clang" : "gcc";
        final String compiler = Linker.resolveCompiler(tool);
        final String version = firstLine(List.of(compiler, "--version"));
        if (version == null) {
            ok = false;
            out.println("  " + tool + "       MISSING: not found on the PATH (or in nabu." + tool + " / NABU_"
                    + tool.toUpperCase() + " / NABU_TOOLCHAIN_BIN)");
            out.println("            install it, see site/backends/llvm/installation.md");
        } else {
            out.println("  " + tool + "       " + version + "  [" + compiler + "]");
            final String libgc = firstLine(List.of(compiler, "-print-file-name=libgc.a"));
            final boolean libgcFound = libgc != null && !libgc.equals("libgc.a") && !libgc.equals("libgc.so");
            final String shared = firstLine(List.of(compiler, "-print-file-name=libgc.so"));
            final boolean sharedFound = shared != null && !shared.equals("libgc.so");
            if (libgcFound || sharedFound) {
                out.println("  libgc     " + (libgcFound ? libgc : shared));
            } else {
                ok = false;
                out.println("  libgc     MISSING: the Boehm collector (--gc BOEHM) cannot be linked; install it "
                        + "(Debian/Ubuntu: libgc-dev, Fedora: gc-devel, Homebrew: bdw-gc)");
            }
        }

        final boolean runtimeBundled = NativeImage.class.getResource("/nabu/runtime/nabu_runtime.c") != null;
        out.println("  runtime   " + (runtimeBundled ? "bundled in the jar" : "not bundled: nabu.runtime must name nabu_runtime.c"));

        final java.nio.file.Path tzdb = java.nio.file.Path.of(System.getProperty("java.home"), "lib", "tzdb.dat");
        out.println("  tzdb.dat  " + (java.nio.file.Files.isRegularFile(tzdb) ? tzdb : "MISSING: time zones will not work"));

        out.println(ok ? "ready to build" : "not ready: fix what is marked MISSING");
        return ok ? 0 : 1;
    }

    private static String firstLine(final List<String> command) {
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            final String line = output.lines().findFirst().orElse("").trim();
            return line.isEmpty() ? null : line;
        } catch (final IOException e) {
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
