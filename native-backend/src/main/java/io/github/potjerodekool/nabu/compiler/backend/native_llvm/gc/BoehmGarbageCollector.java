package io.github.potjerodekool.nabu.compiler.backend.native_llvm.gc;

import io.github.potjerodekool.nabu.compiler.backend.native_llvm.Linker;

import java.util.ArrayList;
import java.util.List;

/** The Boehm-Demers-Weiser conservative collector (libgc): runtime/gc_boehm.c. */
public final class BoehmGarbageCollector implements GarbageCollector {

    @Override
    public String name() {
        return "boehm";
    }

    /**
     * -Dnabu.allocProfile=true: the compiled code allocates through functions of gc_boehm.c that count the bytes and
     * objects per call site (written to the file in NABU_ALLOC_PROF at run time).
     */
    private static boolean allocProfile() {
        return Boolean.getBoolean("nabu.allocProfile");
    }

    /**
     * -Dnabu.gc.debugAlloc=true: the compiled code allocates through the debug allocator of the collector, which puts
     * guard words around every object and reports at each collection the objects that were written past their end
     * (see nabu_dbg_malloc in gc_boehm.c). Slower and bigger; for finding a heap overrun.
     */
    private static boolean debugAlloc() {
        return Boolean.getBoolean("nabu.gc.debugAlloc");
    }

    @Override
    public String allocateSymbol() {
        return allocProfile() ? "nabu_prof_malloc" : debugAlloc() ? "nabu_dbg_malloc" : "GC_malloc";
    }

    @Override
    public String allocateAtomicSymbol() {
        return allocProfile() ? "nabu_prof_malloc_atomic" : debugAlloc() ? "nabu_dbg_malloc_atomic" : "GC_malloc_atomic";
    }

    @Override
    public String runtimeSource() {
        return "gc_boehm.c";
    }

    @Override
    public List<String> compileFlags() {
        final List<String> flags = new ArrayList<>();
        flags.add("-DNABU_GC_BOEHM");
        Linker.homebrewPrefix().ifPresent(prefix -> flags.add("-I" + prefix + "/include"));
        return flags;
    }

    @Override
    public List<String> linkFlags() {
        final List<String> flags = new ArrayList<>();
        Linker.homebrewPrefix().ifPresent(prefix -> flags.add("-L" + prefix + "/lib"));
        final String staticLibrary = staticLibrary();
        if (staticLibrary == null) {
            flags.add("-lgc");
        } else if (staticLibrary.isEmpty()) {
            // GNU ld: this one library from its archive, the rest (libc, libm) shared
            flags.add("-Wl,-Bstatic");
            flags.add("-lgc");
            flags.add("-Wl,-Bdynamic");
        } else {
            flags.add(staticLibrary);
        }
        flags.add("-lpthread");
        return flags;
    }

    /**
     * How libgc.a goes into the executable, so that the machine it runs on needs no libgc: null when it is not linked in
     * (-Dnabu.link.staticGc=false, no archive found, Windows where -static does it), "" on Linux (-Wl,-Bstatic around
     * -lgc), the path of the archive on macOS (clang has no -Bstatic).
     */
    private static String staticLibrary() {
        final String os = System.getProperty("os.name", "").toLowerCase();
        if (!"false".equalsIgnoreCase(System.getProperty("nabu.link.staticGc", "true"))) {
            if (os.contains("mac")) {
                return Linker.homebrewPrefix()
                        .map(prefix -> java.nio.file.Path.of(prefix, "lib", "libgc.a"))
                        .filter(java.nio.file.Files::isRegularFile)
                        .map(java.nio.file.Path::toString)
                        .orElse(null);
            }
            if (os.contains("linux") && archiveOfGcc("libgc.a")) {
                return "";
            }
        }
        return null;
    }

    /** Whether {@code gcc -print-file-name=<name>} finds the archive (it prints the bare name when it does not). */
    private static boolean archiveOfGcc(final String name) {
        try {
            final Process process = new ProcessBuilder("gcc", "-print-file-name=" + name)
                    .redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor() == 0 && output.contains("/")
                    && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(output));
        } catch (final java.io.IOException e) {
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
