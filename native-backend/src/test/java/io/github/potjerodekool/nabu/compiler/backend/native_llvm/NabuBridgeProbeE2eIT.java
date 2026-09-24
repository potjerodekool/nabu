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
 * Probe-fase E4-brug (naam-collision) door de ECHTE officiële
 * {@link NabuCompiler#compile(CompilerOptions)}-pipeline met
 * {@code --backend=LLVM} tot een draaiende native exe.
 *
 * <p><b>De brug in één zin:</b> de frontend emitteert een statische call naar
 * methode {@code m} op owner {@code O} als
 * {@code O.getQualifiedName() + "_" + m}. Voor een default-package klasse
 * {@code nabu} met methode {@code reflect_field_offset} is de IR-callnaam
 * dus {@code nabu_reflect_field_offset} — precies het C-symbool in
 * {@code nabu_runtime.c} (de frontend hoeft het C-symbool dus niet te
 * "kennen": de naam-collision dòet de koppeling).
 *
 * <p><b>De andere helft (frontend-fix, al in de werkboom):</b> een
 * {@code native}-methode zonder body krijgt in {@code IRBuilder.beginFunction}
 * géén entry-block én {@code markExternal()} — dus de backend declareert
 * {@code nabu_reflect_field_offset} als <em>extern</em> (pass 1) en emitteert
 * géén body (pass 3 skip), pass 2.5 declareert de call extern en de linker
 * koppelt aan het C-symbool. Zonder die fix zou de body-loze functie als
 * definitie geëmitteerd worden en zou er géén C-symbool gelinkt worden.
 *
 * <p><b>Probe-programma</b> (één public klasse per bestand, bestand
 * vernoemd naar de klasse):
 *
 * <pre>
 * nabu.nabu    → public class nabu { public static native fun
 *                 reflect_field_offset(typeName: String, fieldName: String): long; }
 * Main.nabu    → public class Main { public fun main(): int {
 *                 return (int) nabu.reflect_field_offset("Main", "count"); } }
 * </pre>
 *
 * <p>De exe draait en geeft de C-return (offset van {@code count} in
 * {@code Main} — hier 0L als eerste/laatste int-veld) door als exit-code 0.
 *
 * <p>EH-loos exe (geen try/catch), dus de launch wordt niet door de
 * Bitdefender-pin op lijkt geblokkeerd — conform het bewezen
 * {@code SourceInheritanceE2eIT}-model.
 */
class NabuBridgeProbeE2eIT {

    @TempDir
    Path tempDir;

    private Path root;
    private Path src;
    private Path out;

    private Path root() {
        if (root != null) return root;

        var rootFile = new File(".").getAbsoluteFile();
        while (!"nabu".equals(rootFile.getName())) {
            rootFile = rootFile.getParentFile();
        }
        root = rootFile.toPath();
        return root;
    }

    @Test
    void defaultPackageNabuBridgeProbeCompilesToRunnableExeReturningZero() throws Exception {
        final var rootPath = root();

        src = Files.createDirectories(tempDir.resolve("src"));
        out = Files.createDirectories(tempDir.resolve("out"));

        // Default-package brugklasse "nabu" — statische native methode die de
        // frontend emitteert als "nabu_reflect_field_offset" = C-symbool.
        Files.writeString(src.resolve("nabu.nabu"),
                "public class nabu {\n" +
                "\n" +
                "    public static native fun reflect_field_offset(\n" +
                "            typeName : String,\n" +
                "            fieldName : String\n" +
                "    ): long;\n" +
                "}\n",
                StandardCharsets.UTF_8);

        Files.writeString(src.resolve("Main.nabu"),
                "public class Main {\n" +
                "\n" +
                "    public count : int;\n" +
                "\n" +
                "    public fun main(): int {\n" +
                "        var offset : long = nabu.reflect_field_offset(\"Main\", \"count\");\n" +
                "        return (int) offset;\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        final var rb = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, src.toAbsolutePath().toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toAbsolutePath().toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.SYSTEM,
                        new File(rootPath.toFile(), "compiler/src/test/resources").getAbsolutePath())
                .build();

        final var diagnostics = new java.util.ArrayList<String>();
        final var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": "
                + d.getMessage(java.util.Locale.getDefault())));

        final int rc = compiler.compile(rb);
        assertEquals(0, rc, "Bridge-probe met --backend=LLVM faalde. Diagnostics:\n"
                + String.join("\n", diagnostics));

        Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(p -> p.getFileName().toString().endsWith(".exe"))
                    .findFirst()
                    .orElse(null);
        }
        assertNotNull(exe, "Geen .exe geproduceerd in " + out);

        final var exePath = exe.toAbsolutePath();
        final String extraBin = "C:\\programs\\mingw64\\mingw64\\bin";
        final var pb = new ProcessBuilder(exePath.toString());
        pb.environment().compute("Path", (k, v) -> extraBin + ";" + (v == null ? "" : v));
        final var proc = pb.redirectErrorStream(true).start();
        final String output = new String(proc.getInputStream().readAllBytes());
        final int exit = proc.waitFor();

        assertEquals(0, exit, "Bridge-probe exe exit-code faalde (verwacht 0 = "
                + "offset van \"count\" als eerste int-veld). Output:\n" + output);
    }
}
