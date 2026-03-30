package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileException;

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
                             String targetTriple) throws CompileException {
        link(List.of(objectFile), executable, targetTriple);
    }

    public static void link(List<Path> objectFiles,
                             Path       executable,
                             String     targetTriple) throws CompileException {
        List<String> cmd = buildCommand(objectFiles, executable, targetTriple);
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
                                              String triple) {
        List<String> cmd = new ArrayList<>();
        if (triple != null && triple.contains("windows")) {
            cmd.add("clang");
            cmd.add("-target"); cmd.add(triple);
            cmd.add("-o"); cmd.add(exe.toString());
            // Verplicht op Windows: subsystem declareren
            cmd.add("-Wl,/subsystem:console");
            // Koppel de MSVC C-runtime
            cmd.add("-lmsvcrt");
            objects.forEach(o -> cmd.add(o.toString()));
            /*
            cmd.add("link.exe");
            cmd.add("/OUT:" + exe);
            cmd.add("/SUBSYSTEM:CONSOLE");
            cmd.add("/NOLOGO");
            objects.forEach(o -> cmd.add(o.toString()));
            */
        } else if (triple != null && triple.contains("apple")) {
            cmd.add("clang");
            cmd.add("-o"); cmd.add(exe.toString());
            objects.forEach(o -> cmd.add(o.toString()));
        } else {
            cmd.add("gcc");
            cmd.add("-o"); cmd.add(exe.toString());
            objects.forEach(o -> cmd.add(o.toString()));
        }
        return cmd;
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
