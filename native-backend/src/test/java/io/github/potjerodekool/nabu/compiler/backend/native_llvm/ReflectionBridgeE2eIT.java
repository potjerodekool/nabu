package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.compiler.NabuCompiler;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Brugprobe (naam-collision) door de <b>officiële</b> pipeline:
 * {@link NabuCompiler#compile(CompilerOptions)} met {@code --backend=LLVM}
 * compilen de default-package brugklasse {@code nabu} (statische {@code native}
 * {@code reflect_field_offset}) plus een {@code Main} met int-veld
 * {@code count}; de brug-exit-code moet de reflect-offset van {@code count}
 * leveren.
 *
 * <p><b>De brug (E4):</b> de frontend emitteert de IR-call als
 * {@code owner.getQualifiedName() + "_" + methode} = {@code nabu_reflect_field_offset}
 * — exact het C-symbool in {@code nabu_runtime.c}. Pass 2.5 declareert de
 * call naar het onbekende symbool extern; de linker koppelt hem aan de
 * C-functie die via {@code nabu_lookup_reflection} de offset van verdachte
 * veld op type Main opzoekt.
 *
 * <p>Reflect-config wordt, conform de groen-bewezen
 * {@code ReflectionRuntimeE2eTest}-route, onder de <b>class-output-root</b>
 * ({@code out/META-INF/native-image/**}) geschreven — dat is de root waar
 * {@code NativeImageConfigScanner.scan} bij de emissie naar kijkt.
 */
class ReflectionBridgeE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void reflectionBridgeProbeReturnsCountOffset() throws Exception {
        NativeToolchain.assumeGcc();

        var root = new File(".").getAbsoluteFile();
        while (!"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }

        Path src = Files.createDirectories(tempDir.resolve("src"));
        Path out = Files.createDirectories(tempDir.resolve("out"));

        Path nativeImageDir = tempDir.resolve("META-INF").resolve("native-image")
                .resolve("picocli-generated");
        Files.createDirectories(nativeImageDir);
        Files.writeString(nativeImageDir.resolve("reflect-config.json"),
                "[\n" +
                "  {\n" +
                "    \"name\" : \"Main\",\n" +
                "    \"allDeclaredConstructors\" : true,\n" +
                "    \"allPublicMethods\" : true,\n" +
                "    \"fields\" : [\n" +
                "      { \"name\" : \"count\" }\n" +
                "    ]\n" +
                "  }\n" +
                "]\n",
                StandardCharsets.UTF_8);

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

        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").toPath().toAbsolutePath().toString());

        var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, src.toAbsolutePath().toString())
                .option(CompilerOption.CLASS_OUTPUT, out.toAbsolutePath().toString())
                .option(CompilerOption.BACKEND, "LLVM")
                .option(CompilerOption.SYSTEM,
                        new File(root, "compiler/src/test/resources").getAbsolutePath())
                .build();

        int rc;
        var diagnostics = new java.util.ArrayList<String>();
        var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": "
                + d.getMessage(java.util.Locale.getDefault())));
        rc = compiler.compile(options);

        assertEquals(0, rc, "NabuCompiler.compile --backend=LLVM faalde. Diagnostics:\n"
                + String.join("\n", diagnostics));

        Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(NativeToolchain::isExecutableOutput)
                    .findFirst()
                    .orElse(null);
        }
        assertNotNull(exe, "Geen .exe geproduceerd in " + out);
        assertTrue(Files.exists(exe), ".exe niet aangemaakt: " + exe);

        ProcessBuilder pb = NativeToolchain.run(exe.toAbsolutePath());
        Process proc = pb.redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertEquals(40, exit,
                "Brug-exe-exit-code faalde (verwacht 40 = offset van \"count\" na de\n"
                        + "40-byte nabu_object-header in het officiële emit-object-model).\n"
                        + "Output:\n" + output);
    }
}
