package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repliceert de cli-demo setup (GreetCommand.nabu met picocli annotaties)
 * direct op de NabuCompiler zodat resolutie-bugs los van de maven-plugin
 * gereproduceerd kunnen worden.
 */
class PicocliGreetCommandIntegrationTest {

    private static final String GREET_COMMAND =
            "package greet;\n" +
            "\n" +
            "import picocli.CommandLine.Command;\n" +
            "import picocli.CommandLine.Option;\n" +
            "\n" +
            "@Command(\n" +
            "        name = \"greet\",\n" +
            "        mixinStandardHelpOptions = true,\n" +
            "        version = {\"greet 1.0\"},\n" +
            "        description = {\"Says hello to someone\"}\n" +
            ")\n" +
            "public class GreetCommand {\n" +
            "\n" +
            "    @Option(names = {\"-n\", \"--name\"}, description = {\"Name to greet\"})\n" +
            "    private name : String = \"world\";\n" +
            "\n" +
            "    @Option(names = {\"-c\", \"--count\"}, description = {\"Number of greetings\"})\n" +
            "    private count : int = 1;\n" +
            "\n" +
            "}\n";

    @TempDir
    Path tempDir;

    @Test
    void loadClassDirectlyWorksInSameContext() throws Exception {
        final var picocliJar = getLocationOfClass(picocli.CommandLine.class);

        final var rootDirectory = resolveCompilerDirectory();
        final var rootPath = rootDirectory.getAbsolutePath();

        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, tempDir.resolve("src").toString())

                .option(CompilerOption.CLASS_PATH, picocliJar)
                .option(CompilerOption.CLASS_OUTPUT, tempDir.resolve("out").toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("generated").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();

        final var compiler = new NabuCompiler();
        final var context = compiler.configure(options);
        final var loader = context.getClassElementLoader();
        final var unnamed = context.getModules().getUnnamedModule();

        final var command = loader.loadClass(unnamed, "picocli.CommandLine.Command");
        assertNotNull(command, "loadClass(picocli.CommandLine.Command) moet laden");
        System.out.println("DIRECT LOAD: " + command.getClass().getName() + " qualified=" + command.getQualifiedName());
    }

    @Test
    void greetCommandResolvesPicocliAnnotations() throws Exception {
        final var sourceDir = tempDir.resolve("src");
        final var sourceFile = sourceDir.resolve("greet/GreetCommand.nabu");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, GREET_COMMAND);

        final var outDir = tempDir.resolve("out");

        final var picocliJar = getLocationOfClass(picocli.CommandLine.class);
        final var codegenJar = System.getProperty("user.home")
                + "/.m2/repository/info/picocli/picocli-codegen/4.7.6/picocli-codegen-4.7.6.jar";

        final var rootDirectory = resolveCompilerDirectory();
        final var rootPath = rootDirectory.getAbsolutePath();

        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, sourceDir.toString())
                .option(CompilerOption.CLASS_PATH, picocliJar)
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("generated").toString())
                .option(CompilerOption.ANNOTATION_PROCESSOR_PATH, picocliJar + File.pathSeparator + codegenJar)
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();

        final var compiler = new NabuCompiler();
        final var diagnostics = new AtomicInteger();
        compiler.setListener(diagnostic -> {
            if (diagnostic.getKind().name().equals("ERROR")) {
                diagnostics.incrementAndGet();
                System.out.println("DIAG: " + diagnostic.getMessage(java.util.Locale.ROOT));
            }
        });

        final var result = compiler.compile(options);

        assertEquals(0, result, "Compile moet slagen, diagnostics=" + diagnostics.get());
        assertEquals(0, diagnostics.get());

        final var generatedConfigDir = outDir
                .resolve("META-INF/native-image/picocli-generated");
        assertTrue(Files.isDirectory(generatedConfigDir),
                "picocli-codegen moet native-image config hebben gegenereerd in " + generatedConfigDir);
    }

    private File resolveCompilerDirectory() {
        var current = new File(".").getAbsoluteFile();
        while (!current.isDirectory() || !"nabu".equals(current.getName())) {
            current = current.getParentFile();
        }
        return new File(current, "compiler");
    }

    private String getLocationOfClass(final Class<?> clazz) {
        try {
            return Path.of(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}