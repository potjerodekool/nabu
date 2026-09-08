package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.util.CompileException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Linkt één of meer object files naar een uitvoerbaar bestand.
 *
 * Platform → linker:
 *   Windows  → link.exe (MSVC)
 *   macOS    → clang
 *   Linux    → gcc
 *
 * Het host-triple wordt automatisch bepaald via hostTriple().
 */
public class Linker {

    private Linker() {}

    public static void link(Path objectFile,
                             Path executable,
                             String targetTriple,
                             CompileOptions.GcStrategy gcStrategy) throws CompileException {
        link(List.of(objectFile), executable, targetTriple, gcStrategy);
    }

    public static void link(List<Path> objectFiles,
                             Path       executable,
                             String     targetTriple,
                             CompileOptions.GcStrategy gcStrategy) throws CompileException {
        List<String> cmd = buildCommand(objectFiles, executable, targetTriple, gcStrategy);
        try {
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int    exit   = process.waitFor();
            if (exit != 0)
                throw new CompileException(
                    "Linker mislukt (exit " + exit + "):\n" + output);
        } catch (IOException e) {
            throw new CompileException(
                "Linker kon niet worden gestart: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompileException("Linker onderbroken", e);
        }
    }

    private static List<String> buildCommand(List<Path> objects,
                                              Path exe,
                                              String triple,
                                              CompileOptions.GcStrategy gcStrategy) {
        List<String> cmd = new ArrayList<>();
        if (triple != null && triple.contains("windows")) {
            if (triple.contains("gnu")) {
                // MinGW (SEH) pad: link met gcc en compileer+link de nabu
                // runtime mee (voor de SEH-persoonlijkheid nabu_seh_personality,
                // nabu_throw/_Unwind, nabu_new_object, etc.). gcc koppelt
                // libgcc_s_seh automatisch.
                cmd.add(resolveCompiler("gcc"));
                cmd.add("-o"); cmd.add(exe.toString());
                objects.forEach(o -> cmd.add(o.toString()));
                Path runtimeSrc = runtimeSource();
                if (runtimeSrc != null) {
                    cmd.add(runtimeSrc.toString());
                }
                addGcLibraries(cmd, gcStrategy);
            } else {
                cmd.add("clang");
                cmd.add("-target"); cmd.add(triple);
                cmd.add("-o"); cmd.add(exe.toString());
                cmd.add("-Wl,/subsystem:console");
                cmd.add("-lmsvcrt");
                objects.forEach(o -> cmd.add(o.toString()));
                addGcLibraries(cmd, gcStrategy);
            }
        } else if (triple != null && triple.contains("apple")) {
            cmd.add("clang");
            cmd.add("-o"); cmd.add(exe.toString());
            objects.forEach(o -> cmd.add(o.toString()));
            addGcLibraries(cmd, gcStrategy);
        } else {
            cmd.add("gcc");
            cmd.add("-o"); cmd.add(exe.toString());
            objects.forEach(o -> cmd.add(o.toString()));
            Path runtimeSrc = runtimeSource();
            if (runtimeSrc != null) {
                cmd.add(runtimeSrc.toString());
            }
            addGcLibraries(cmd, gcStrategy);
        }
        return cmd;
    }

    /**
     * Locatie van nabu_runtime.c. Eerst via de systeemproperty
     * {@code nabu.runtime}, dan de env-var {@code NABU_RUNTIME_SRC}; dan het
     * repo-relatieve {@code runtime/nabu_runtime.c}; als laatste fallback de
     * in de plugin-jar gebundelde kopie ({@code nabu/runtime/nabu_runtime.c},
     * uitgepakt naar een temp-dir). Die bundel dekt een maven-deployment
     * zonder repo-checkout.
     */
    private static Path runtimeSource() {
        String prop = System.getProperty("nabu.runtime");
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        String env = System.getenv("NABU_RUNTIME_SRC");
        if (env != null && !env.isBlank()) return Path.of(env);
        Path guess = Path.of("runtime", "nabu_runtime.c");
        if (java.nio.file.Files.exists(guess)) return guess;
        return bundledRuntime();
    }

    private static Path bundledRuntimeCache;

    private static Path bundledRuntime() {
        try {
            if (bundledRuntimeCache != null
                    && java.nio.file.Files.exists(bundledRuntimeCache)) {
                return bundledRuntimeCache;
            }

            final ClassLoader loader = Linker.class.getClassLoader();
            if (loader == null) {
                return null;
            }

            try (var in = loader.getResourceAsStream("nabu/runtime/nabu_runtime.c")) {
                if (in == null) {
                    return null;
                }

                final Path dir = java.nio.file.Files.createTempDirectory("nabu-runtime");
                final Path src = dir.resolve("nabu_runtime.c");
                java.nio.file.Files.copy(in, src,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // Header ernaast, zodat '#include "nabu_runtime.h"' uit de
                // include-map van de .c zelf wordt opgelost.
                try (var header = loader.getResourceAsStream("nabu/runtime/nabu_runtime.h")) {
                    if (header != null) {
                        java.nio.file.Files.copy(header, dir.resolve("nabu_runtime.h"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                bundledRuntimeCache = src;
                return src;
            }
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Resolveert het pad van een build-tool (gcc/clang). Eerst via een
     * systeemproperty ({@code nabu.<tool>}), dan de env-var
     * ({@code NABU_<TOOL>}); anders via de PATH en bekende install-locaties
     * (winlibs/MinGW).
     */
    private static String resolveCompiler(String tool) {
        String prop = System.getProperty("nabu." + tool);
        if (prop != null && !prop.isBlank()) return prop;
        String env = System.getenv("NABU_" + tool.toUpperCase());
        if (env != null && !env.isBlank()) return env;
        String exe = "gcc".equals(tool) ? "gcc.exe" : tool + ".exe";
        Path[] candidates = {
                Path.of("C:\\programs\\mingw64\\mingw64\\bin", exe),
                Path.of("C:\\mingw64\\bin", exe),
                Path.of("C:\\msys64\\mingw64\\bin", exe),
        };
        for (Path c : candidates) {
            if (java.nio.file.Files.exists(c)) return c.toString();
        }
        return tool;
    }

    private static void addGcLibraries(List<String> cmd,
                                        CompileOptions.GcStrategy gcStrategy) {
        if (gcStrategy == CompileOptions.GcStrategy.BOEHM) {
            cmd.add("-lgc");
            cmd.add("-lpthread");
        }
    }

    /**
     * Kiest het target-triple voor codegen + linken wanneer de caller er geen
     * expliciet (CompileOptions.targetTriple) opgaf. Op Windows prefereren we
     * het MinGW/SEH-triple ({@code x86_64-w64-windows-gnu}) zodra daar een gcc
     * gevonden wordt: onze runtime en de Itanium-LSDA/personality draaien op
     * de GNU-toolchain (libgcc_s_seh). Zonder gcc vallen we terug op de
     * LLVM-default (windows-msvc).
     */
    public static String guessTargetTriple(final String defaultTriple) {
        final String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return defaultTriple;
        }
        final String gcc = resolveCompiler("gcc");
        final boolean hasGcc = !"gcc".equals(gcc) && java.nio.file.Files.exists(Path.of(gcc));
        return hasGcc ? "x86_64-w64-windows-gnu" : defaultTriple;
    }

    /**
     * Retourneert het LLVM-triple van het huidige systeem.
     */
    public static String hostTriple() {
        String os   = System.getProperty("os.name",  "").toLowerCase();
        String arch = System.getProperty("os.arch",  "").toLowerCase();
        String llvmArch = (arch.contains("aarch64") || arch.contains("arm64"))
                ? "aarch64" : "x86_64";
        if (os.contains("win"))
            return llvmArch + "-pc-windows-msvc";
        if (os.contains("mac") || os.contains("darwin"))
            return llvmArch + "-apple-macosx13.0";
        return llvmArch + "-unknown-linux-gnu";
    }
}
