package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.util.CompileException;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Linkt één of meer object files naar een uitvoerbaar bestand.
 *
 * Platform → linker:
 *   Windows  → gcc (MinGW/SEH); MSVC-targets worden expliciet geweigerd
 *   macOS    → clang
 *   Linux    → gcc
 *
 * Het target-triple wordt door de caller bepaald (zie
 * {@link #guessTargetTriple(String)}); {@link #hostTriple()} geeft het
 * LLVM-triple van het huidige systeem.
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
                    "Linker failed (exit " + exit + "):\n" + output);
        } catch (IOException e) {
            throw new CompileException(
                "Could not start the linker: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompileException("Linker onderbroken", e);
        }
    }

    /**
     * Bouwt de commandoregel voor het gegeven target. Package-private zodat de
     * tests kunnen controleren dat het juiste platformpad gekozen wordt zonder
     * een echte linkerdraai te doen.
     */
    static List<String> buildCommand(List<Path> objects,
                                     Path exe,
                                     String triple,
                                     CompileOptions.GcStrategy gcStrategy) throws CompileException {
        List<String> cmd = new ArrayList<>();
        if (triple != null && triple.contains("windows")) {
            if (triple.contains("gnu")) {
                // MinGW (SEH) pad: link met gcc en compileer+link de nabu
                // runtime mee (voor de SEH-persoonlijkheid nabu_seh_personality,
                // nabu_throw/_Unwind, nabu_init_object, etc.). gcc koppelt
                // libgcc_s_seh automatisch.
                cmd.add(resolveCompiler("gcc"));
                cmd.add("-g"); cmd.add("-gdwarf-4");
                cmd.add("-o"); cmd.add(exe.toString());
                objects.forEach(o -> cmd.add(o.toString()));
                addRuntimeSources(cmd, gcStrategy);
                addGcLibraries(cmd, gcStrategy);
            } else {
                throw new CompileException(msvcUnsupported(triple));
            }
        } else if (triple != null && triple.contains("apple")) {
            cmd.add("clang");
            cmd.add("-g");
            cmd.add("-o"); cmd.add(exe.toString());
            objects.forEach(o -> cmd.add(o.toString()));
            addRuntimeSources(cmd, gcStrategy);
            addGcLibraries(cmd, gcStrategy);
            cmd.add("-lm");
        } else {
            cmd.add("gcc");
            cmd.add("-g");
            cmd.add("-rdynamic");
            cmd.add("-o"); cmd.add(exe.toString());
            objects.forEach(o -> cmd.add(o.toString()));
            addRuntimeSources(cmd, gcStrategy);
            addGcLibraries(cmd, gcStrategy);
            cmd.add("-lm");
        }
        return cmd;
    }

    private static void addRuntimeSources(List<String> cmd,
                                          CompileOptions.GcStrategy gcStrategy) {
        if (gcStrategy == CompileOptions.GcStrategy.BOEHM) {
            cmd.add("-DNABU_GC_BOEHM");
        }
        runtimeSources().forEach(src -> cmd.add(src.toString()));
    }

    /**
     * MSVC-targets kunnen nog niet gelinkt worden. De backend emitteert een
     * DWARF/LSDA-unwind met de eigen personality {@code nabu_seh_personality}
     * uit {@code runtime/nabu_runtime.c}; dat mechanisme bestaat niet in een
     * MSVC-CRT. MSVC heeft daarvoor een ander mechanisme nodig (x64 scope
     * tables + {@code __C_specific_handler}), wat nog niet geïmplementeerd is.
     *
     * Vroeger probeerde deze tak clang met {@code -lmsvcrt} aan te roepen, maar
     * voegde de runtime niet toe aan de linkregel. Dat eindigde in
     * {@code undefined symbol: nabu_throw} zonder uitleg. Nu stappen we met een
     * concrete reden af en wijzen we naar het wel ondersteunde pad.
     */
    private static String msvcUnsupported(String triple) {
        return "Linking for an MSVC target is not supported yet (" + triple + ").\n"
             + "The exception mechanism of this backend is based on DWARF/LSDA\n"
             + "(nabu_seh_personality + its own LSDA personality in runtime/nabu_runtime.c),\n"
             + "which does not exist in an MSVC CRT; MSVC uses x64 scope tables with "
             + "__C_specific_handler.\n"
             + "On Windows, use the MinGW/SEH target, for example "
             + "CompileOptions.forTarget(\"x86_64-w64-windows-gnu\").";
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
        return bundledRuntime("nabu_runtime.c");
    }

    /**
     * Alle C-bronnen die meegecompileerd worden: de runtime (SEH/personality,
     * objectmodel) en jcl_lite (java.lang/java.util-implmentaties). Siblings
     * van {@link #runtimeSource()}; ontbrekende siblings worden overgeslagen.
     */
    private static List<Path> runtimeSources() {
        Path main = runtimeSource();
        if (main == null) return List.of();
        List<Path> srcs = new ArrayList<>();
        srcs.add(main);
        Path jcl = main.resolveSibling("jcl_lite.c");
        if (java.nio.file.Files.exists(jcl)) srcs.add(jcl);
        return srcs;
    }

    private static Path bundledRuntimeCache;

    private static Path bundledRuntime(String fileName) {
        try {
            if (bundledRuntimeCache != null
                    && java.nio.file.Files.exists(bundledRuntimeCache.resolve(fileName))) {
                return bundledRuntimeCache.resolve(fileName);
            }

            final ClassLoader loader = Linker.class.getClassLoader();
            if (loader == null) {
                return null;
            }

            try (var in = loader.getResourceAsStream("nabu/runtime/" + fileName)) {
                if (in == null) {
                    return null;
                }

                final Path dir = java.nio.file.Files.createTempDirectory("nabu-runtime");
                final Path src = dir.resolve(fileName);
                java.nio.file.Files.copy(in, src,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                for (final String extra : new String[] {"nabu_runtime.c", "nabu_runtime.h", "jcl_lite.c"}) {
                    if (extra.equals(fileName)) continue;
                    try (var e = loader.getResourceAsStream("nabu/runtime/" + extra)) {
                        if (e != null) {
                            java.nio.file.Files.copy(e, dir.resolve(extra),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }

                bundledRuntimeCache = dir;
                return src;
            }
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Resolveert het pad van een build-tool (gcc/clang). Zoekvolgorde:
     * <ol>
     *   <li>systeemproperty {@code nabu.<tool>}</li>
     *   <li>env-var {@code NABU_<TOOL>}</li>
     *   <li>de map uit {@link #toolchainBin()} (property {@code nabu.toolchain.bin}
     *       of env-var {@code NABU_TOOLCHAIN_BIN})</li>
     *   <li>de PATH</li>
     *   <li>gangbare Windows-installatielocaties</li>
     * </ol>
     * Wordt niets gevonden, dan wordt de kale toolnaam teruggegeven; de
     * {@link ProcessBuilder} probeert die dan zelf te vinden.
     */
    public static String resolveCompiler(String tool) {
        String prop = System.getProperty("nabu." + tool);
        if (prop != null && !prop.isBlank()) return prop;
        String env = System.getenv("NABU_" + tool.toUpperCase());
        if (env != null && !env.isBlank()) return env;

        String exe = executableName(tool);
        Path toolchainBin = toolchainBin();
        if (toolchainBin != null) {
            Path candidate = toolchainBin.resolve(exe);
            if (java.nio.file.Files.exists(candidate)) return candidate.toString();
        }

        Path onPath = findOnPath(exe);
        if (onPath != null) return onPath.toString();

        for (Path c : wellKnownWindowsToolDirs()) {
            Path candidate = c.resolve(exe);
            if (java.nio.file.Files.exists(candidate)) return candidate.toString();
        }

        return tool;
    }

    /**
     * Map waarin de native toolchain (gcc/clang) staat, indien geconfigureerd
     * via de systeemproperty {@code nabu.toolchain.bin} of de env-var
     * {@code NABU_TOOLCHAIN_BIN}. {@code null} als er niets is geconfigureerd.
     * Vooral nuttig wanneer de MinGW-bin niet op de PATH staat: dezelfde map
     * kan dan ook aan een subprocess worden doorgegeven zodat de runtime-DLL's
     * (libgcc_s_seh-1.dll e.d.) worden gevonden.
     */
    public static Path toolchainBin() {
        String prop = System.getProperty("nabu.toolchain.bin");
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        String env = System.getenv("NABU_TOOLCHAIN_BIN");
        if (env != null && !env.isBlank()) return Path.of(env);
        return null;
    }

    private static String executableName(final String tool) {
        final var os = System.getProperty("os.name", "").toLowerCase();
        final var windows = os.contains("win");
        return "gcc".equals(tool)
                ? (windows ? "gcc.exe" : "gcc")
                : (windows ? tool + ".exe" : tool);
    }

    private static List<Path> wellKnownWindowsToolDirs() {
        final List<Path> dirs = new ArrayList<>();

        // Gangbare MinGW/MSYS-installaties. Geen machine-specifieke paden: een
        // eigen installatie hoort via nabu.toolchain.bin / NABU_TOOLCHAIN_BIN.
        dirs.add(Path.of("C:\\mingw64\\bin"));
        dirs.add(Path.of("C:\\msys64\\mingw64\\bin"));
        dirs.add(Path.of("C:\\msys64\\ucrt64\\bin"));
        dirs.add(Path.of("C:\\ProgramData\\mingw64\\mingw64\\bin"));
        return dirs;
    }

    private static Path findOnPath(final String exe) {
        final String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) return null;

        for (String entry : pathEnv.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            try {
                Path candidate = Path.of(entry).resolve(exe);
                if (java.nio.file.Files.isExecutable(candidate)) return candidate;
            } catch (InvalidPathException ignored) {
                // Onbruikbare PATH-entry: overslaan.
            }
        }

        return null;
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
