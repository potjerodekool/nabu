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
 * Spike 0.4: een echte .java-bron op de SOURCE_PATH wordt als source-unit
 * in dezelfde batch opgenomen als een .nabu-bron, en beide compileren naar
 * class output (cross-call .nabu -> .java).
 */
class JavaAndNabuBatchTest {

    @TempDir
    Path tempDir;

    @Test
    void javaSourceInBatchCompilesAlongsideNabu() throws Exception {
        final var srcDir = tempDir.resolve("src");

        final var nabuFile = srcDir.resolve("greet/GreetCommand.nabu");
        Files.createDirectories(nabuFile.getParent());
        Files.writeString(nabuFile,
                "package greet;\n" +
                "\n" +
                "public class GreetCommand {\n" +
                "\n" +
                "    private name : String = \"world\";\n" +
                "\n" +
                "}\n");

        final var javaFile = srcDir.resolve("greet/NameProvider.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile,
                "package greet;\n" +
                "\n" +
                "public class NameProvider {\n" +
                "    public String provide() {\n" +
                "        return \"world\";\n" +
                "    }\n" +
                "}\n");

        final var outDir = tempDir.resolve("out");
        final var rootDirectory = resolveCompilerDirectory();
        final var rootPath = rootDirectory.getAbsolutePath();

        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "/src/test/resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("generated").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();

        final var compiler = new NabuCompiler();
        final var diagnostics = new AtomicInteger();
        final var messages = new java.util.ArrayList<String>();
        compiler.setListener(diagnostic -> {
            if (diagnostic.getKind().name().equals("ERROR")) {
                diagnostics.incrementAndGet();
                messages.add(diagnostic.getMessage(java.util.Locale.ROOT).toString());
            }
        });

        final var result = compiler.compile(options);

        assertEquals(0, result, "Compile moet slagen. diagnostics=" + diagnostics.get() + "\n" + String.join("\n", messages));
        assertEquals(0, diagnostics.get(), "Geen errors verwacht:\n" + String.join("\n", messages));

        assertTrue(Files.exists(outDir.resolve("greet/GreetCommand.class")),
                "GreetCommand.class (uit .nabu) moet gegenereerd zijn");
        assertTrue(Files.exists(outDir.resolve("greet/NameProvider.class")),
                "NameProvider.class (uit .java) moet gegenereerd zijn");
    }

    private File resolveCompilerDirectory() {
        var current = new File(".").getAbsoluteFile();
        while (!current.isDirectory() || !"nabu".equals(current.getName())) {
            current = current.getParentFile();
        }
        return new File(current, "compiler");
    }
}