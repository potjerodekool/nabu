package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Types uit JDK-modules (zoals javax.annotation.processing) moeten
 * resolveerbaar zijn vanuit de unnamed module. De unnamed module vult zijn
 * zichtbare pakketten uit de exports van de systeemmodules; die modules moeten
 * dus bekend zijn vóór de unnamed module voor het eerst gecompleteerd wordt.
 */
class JdkModuleTypeResolutionTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesTypesFromNonBaseJdkModules() throws Exception {
        final var srcDir = Files.createDirectories(tempDir.resolve("src/probe"));
        Files.writeString(srcDir.resolve("Gen.java"),
                "package probe;\n" +
                "\n" +
                "import javax.annotation.processing.Generated;\n" +
                "import javax.lang.model.element.Element;\n" +
                "import javax.tools.ToolProvider;\n" +
                "import java.util.List;\n" +
                "\n" +
                "public class Gen {\n" +
                "    @Generated(\"x\")\n" +
                "    public int value(final Element element) {\n" +
                "        final List<String> names = List.of(element.getSimpleName().toString());\n" +
                "        return ToolProvider.getSystemJavaCompiler() == null ? 0 : names.size();\n" +
                "    }\n" +
                "}\n");

        final var outDir = tempDir.resolve("out");

        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, tempDir.resolve("src").toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("generated").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();

        final var compiler = new NabuCompiler();
        final var diagnostics = new AtomicInteger();
        final var messages = new ArrayList<String>();
        compiler.setListener(diagnostic -> {
            if (diagnostic.getKind().name().equals("ERROR")) {
                diagnostics.incrementAndGet();
                messages.add(diagnostic.getMessage(java.util.Locale.ROOT).toString());
            }
        });

        final var result = compiler.compile(options);

        assertEquals(0, result, "Compile moet slagen:\n" + String.join("\n", messages));
        assertEquals(0, diagnostics.get(), "Geen errors verwacht:\n" + String.join("\n", messages));
        assertTrue(Files.exists(outDir.resolve("probe/Gen.class")),
                "Gen.class moet gegenereerd zijn");
    }
}
