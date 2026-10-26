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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code instanceof} met een interface als doeltype, native.
 *
 * <p>{@code nabu_instanceof} liep alleen de super-klasseketen af, dus
 * {@code obj instanceof SomeInterface} was altijd false. picocli's
 * {@code ObjectScope.asScope(scope)} pakte daardoor elk scope-object opnieuw
 * in. De type-info draagt nu de (platgeslagen) interfacelijst; die van een
 * super-klasse telt mee via de keten, die van een superinterface via de lijst.
 *
 * <p>Programma: {@code Loud implements Greeter}, {@code Greeter extends
 * Named}, {@code Louder extends Loud}, {@code Plain} implementeert niets.
 * Bits: 1 = Loud is Greeter, 2 = Louder is Greeter (via super-klasse),
 * 4 = Loud is Named (via superinterface), 8 = Plain is Greeter (mag niet).
 * Verwacht: 1 + 2 + 4 = 7.
 */
class InstanceofInterfaceE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void instanceofMatchesImplementedInterfaces() throws Exception {
        NativeToolchain.assumeGcc();

        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }

        write("Named.nabu", """
                public interface Named {
                }
                """);
        write("Greeter.nabu", """
                public interface Greeter extends Named {
                }
                """);
        write("Loud.nabu", """
                public class Loud implements Greeter {
                }
                """);
        write("Louder.nabu", """
                public class Louder extends Loud {
                }
                """);
        write("Plain.nabu", """
                public class Plain {
                }
                """);
        Path src = write("Main.nabu", """
                public class Main {

                    public fun main(): int {
                        var loud : Object = new Loud();
                        var louder : Object = new Louder();
                        var plain : Object = new Plain();
                        var result : int = 0;
                        if (loud instanceof Greeter) {
                            result = result + 1;
                        }
                        if (louder instanceof Greeter) {
                            result = result + 2;
                        }
                        if (loud instanceof Named) {
                            result = result + 4;
                        }
                        if (plain instanceof Greeter) {
                            result = result + 8;
                        }
                        return result;
                    }
                }
                """);

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
        assertEquals(0, rc, "NabuCompiler.compile met --backend=LLVM faalde. Diagnostics:\n"
                + String.join("\n", diagnostics));

        Path exe;
        try (Stream<Path> walk = Files.walk(out)) {
            exe = walk.filter(NativeToolchain::isExecutableOutput)
                    .findFirst()
                    .orElse(null);
        }
        assertNotNull(exe, "Geen .exe geproduceerd in " + out);

        Process proc = NativeToolchain.run(exe.toAbsolutePath()).redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertEquals(7, exit, "Verwacht 1 + 2 + 4 (Plain is geen Greeter). Output:\n" + output
                + "\nType-info:\n" + typeInfoLines(out));
    }

    private static String typeInfoLines(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".ll"))
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p).stream()
                                    .filter(l -> l.contains("Etype_info =") || l.contains("Louder_init")
                                            || l.contains(".type.itfs."));
                        } catch (Exception e) {
                            return Stream.of("(unreadable " + p + ")");
                        }
                    })
                    .reduce("", (a, b) -> a + b + "\n");
        } catch (Exception e) {
            return "(walk failed: " + e + ")";
        }
    }

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
