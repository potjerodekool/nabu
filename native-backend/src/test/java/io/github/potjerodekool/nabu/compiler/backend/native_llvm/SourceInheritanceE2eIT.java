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
 * Ervigings-e2e op BRONNIVEAU: een échte {@code .nabu}-bron met {@code extends},
 * constructor-{@code super(...)} en een {@code @Override}-methode gaat door de
 * officiële {@link NabuCompiler#compile(CompilerOptions)}-pipeline met
 * {@code --backend=LLVM} tot een draaiende native exe met de overlap-waarde als
 * exit-code.
 *
 * Programma: Animal(age, getAge), Dog : Animal(degree, getAge = age + degree),
 * Main.main → {@code new Dog(4, 5)} via een Animal-typ-referentie → 9.
 *
 * Anders dan de exception-harness programmeer je hier géén try/catch: de exe is
 * EH-loos, dus de launch wordt niet door de Bitdefender-pin geblokkeerd. De
 * aangepaste main-brug geeft een {@code fun main(): int} door als exit-code.
 */
class SourceInheritanceE2eIT {

    @TempDir
    Path tempDir;

    @Test
    void nabuSourceInheritanceCompilesToRunnableExeReturningOverriddenSum() throws Exception {
        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }

        // Eén public klasse per bronbestand, bestand vernoemd naar de klasse
        // (nabu volgt de Java-public-per-file-regel). De drie klassen zitten
        // dus in drie bestanden; de backend bakt ze via één batch samen.
        Files.writeString(tempDir.resolve("Animal.nabu"),
                "public class Animal {\n" +
                "\n" +
                "    public age : int;\n" +
                "\n" +
                "    public Animal(age : int) {\n" +
                "        this.age = age;\n" +
                "    }\n" +
                "\n" +
                "    public fun getAge(): int {\n" +
                "        return this.age;\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        Files.writeString(tempDir.resolve("Dog.nabu"),
                "public class Dog extends Animal {\n" +
                "\n" +
                "    public degree : int;\n" +
                "\n" +
                "    public Dog(age : int, degree : int) {\n" +
                "        super(age);\n" +
                "        this.degree = degree;\n" +
                "    }\n" +
                "\n" +
                "    public fun getAge(): int {\n" +
                "        return this.age + this.degree;\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        Path src = tempDir.resolve("Main.nabu");
        Files.writeString(src,
                "public class Main {\n" +
                "\n" +
                "    public fun main(): int {\n" +
                "        var a : Animal = new Dog(4, 5);\n" +
                "        return a.getAge();\n" +
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

        String extraBin = "C:\\programs\\mingw64\\mingw64\\bin";
        ProcessBuilder pb = new ProcessBuilder(exe.toAbsolutePath().toString());
        pb.environment().compute("Path", (k, v) -> extraBin + ";" + (v == null ? "" : v));
        Process proc = pb.redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertEquals(9, exit,
                "Expected exit 9 (overridden getAge = 4 + 5). Output:\n" + output);
    }
}