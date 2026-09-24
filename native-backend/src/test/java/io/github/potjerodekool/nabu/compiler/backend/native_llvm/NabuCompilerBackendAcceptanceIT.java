package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptatiecriterium Fase 5 (wiring): een écht {@code .nabu}-bronbestand op
 * schijf wordt via {@link NabuCompiler#compile(CompilerOptions)} met
 * {@code --backend=LLVM} volledig gecompileerd tot een native executable die
 * correct draait (exit 0).
 *
 * De LLVM-backend (plugin-naam "LLVM") wordt via de plugin-registry geselecteerd
 * (compiler + native-backend + nabu-lang staan op de testclasspath). De backend
 * synthetiseert de {@code main}-brug voor {@code Main_main}, leidt het
 * x86_64-w64-windows-gnu-triple af van de gevonden MinGW-gcc, compileert naar
 * een .o en linkt met gcc + nabu_runtime.c tot {@code Main.exe}.
 *
 * Het programma gooit een {@code Exception}; de nabu-syntax is
 * {@code catch (e : Exception)}. Wordt de exception níet gevangen, abort
 * nabu_throw (exit ongelijk 0); exit 0 bewijst dat de nabu_seh_personality +
 * LSDA het landingspad installeerde en de catch-handler uitvoerde.
 */
class NabuCompilerBackendAcceptanceIT {

    @TempDir
    Path tempDir;

    @Test
    void nabuFileCompilesViaLlvmBackendToRunnableExe() throws Exception {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }

        Path src = tempDir.resolve("Main.nabu");
        Files.writeString(src,
                "public class Main {\n" +
                "\n" +
                "    fun main(args: String[]): void {\n" +
                "        try {\n" +
                "            throw new Exception();\n" +
                "        }\n" +
                "        catch (e : Exception) {\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());

        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, src.getParent().toAbsolutePath().toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toAbsolutePath().toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.SYSTEM,
                        new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();

        int rc;
        var diagnostics = new java.util.ArrayList<String>();
        var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        rc = compiler.compile(options);

        assertEquals(0, rc, "NabuCompiler.compile met --backend=LLVM faalde. Diagnostics:\n" +
                String.join("\n", diagnostics));

        Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(p -> p.getFileName().toString().endsWith(".exe"))
                    .findFirst()
                    .orElse(null);
        }
        assertNotNull(exe, "Geen .exe geproduceerd in " + out);
        assertTrue(Files.exists(exe), ".exe niet aangemaakt: " + exe);

        // libgcc_s_seh-1.dll zit in de MinGW-bin; zet die op PATH voor de run.
        String extraBin = "C:\\programs\\mingw64\\mingw64\\bin";
        ProcessBuilder pb = new ProcessBuilder(exe.toAbsolutePath().toString());
        pb.environment().compute("Path", (k, v) -> extraBin + ";" + (v == null ? "" : v));
        Process proc = pb.redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertEquals(0, exit, "Native executable draait niet (exit " + exit + "). Output:\n" + output);
    }
}