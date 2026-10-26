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
 * Ervigings-e2e op BRONNIVEAU voor <b>interface-dispatch</b>.
 *
 * Twee klassen implementeren dezelfde interface, en de aanroep gebeurt via een
 * variabele van het interfacetype. Dat kan alleen werken als de itable van de
 * dynamische klasse naar de eigen implementatie wijst — een directe call naar de
 * interface zou 0 opleveren (abstracte stub).
 *
 * Programma: Greeter.greet(); Loud(base) = base + 1; Quiet = 100;
 * Main.main → {@code new Loud(40)} en {@code new Quiet()} door een Greeter
 * verwijzing → 41 + 100 = 141.
 */
class InterfaceDispatchE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void nabuInterfaceDispatchCallsTheRuntimeImplementation() throws Exception {
        NativeToolchain.assumeGcc();

        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }

        Files.writeString(tempDir.resolve("Greeter.nabu"),
                "public interface Greeter {\n" +
                "\n" +
                "    public fun greet(): int;\n" +
                "}\n",
                StandardCharsets.UTF_8);

        Files.writeString(tempDir.resolve("Loud.nabu"),
                "public class Loud implements Greeter {\n" +
                "\n" +
                "    public base : int;\n" +
                "\n" +
                "    public Loud(base : int) {\n" +
                "        this.base = base;\n" +
                "    }\n" +
                "\n" +
                "    public fun greet(): int {\n" +
                "        return this.base + 1;\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        Files.writeString(tempDir.resolve("Quiet.nabu"),
                "public class Quiet implements Greeter {\n" +
                "\n" +
                "    public fun greet(): int {\n" +
                "        return 100;\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        Path src = tempDir.resolve("Main.nabu");
        Files.writeString(src,
                "public class Main {\n" +
                "\n" +
                "    public fun main(): int {\n" +
                "        var loud : Greeter = new Loud(40);\n" +
                "        var quiet : Greeter = new Quiet();\n" +
                "        return loud.greet() + quiet.greet();\n" +
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

        var diagnostics = new java.util.ArrayList<String>();
        var compiler = new NabuCompiler();
        compiler.setListener(d -> diagnostics.add(d.getKind() + ": " + d.getMessage(java.util.Locale.getDefault())));
        int rc = compiler.compile(options);

        assertEquals(0, rc, "NabuCompiler.compile met --backend=LLVM faalde. Diagnostics:\n" +
                String.join("\n", diagnostics) + "\nLL:\n" + readLl(out, "Greeter"));

        Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(NativeToolchain::isExecutableOutput)
                    .findFirst()
                    .orElse(null);
        }
        assertNotNull(exe, "Geen .exe geproduceerd in " + out);

        ProcessBuilder pb = NativeToolchain.run(exe.toAbsolutePath());
        Process proc = pb.redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertEquals(141, exit,
                "Expected exit 141 (Loud(40).greet() = 41 plus Quiet.greet() = 100). Output:\n" + output);
    }

    private static String readLl(Path dir, String name) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().equals(name + ".ll"))
                    .findFirst()
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (Exception e) {
                            return "(unreadable: " + e + ")";
                        }
                    })
                    .orElse("(geen " + name + ".ll gevonden in " + dir + ")");
        } catch (Exception e) {
            return "(walk failed: " + e + ")";
        }
    }
}