package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1: conditional (ternary) expressions end-to-end via de officiële
 * NabuCompiler.compile-pipeline (ASM-backend): parse → resolve → lower → IR →
 * bytecode-validatie.
 */
class ConditionalExpressionCompileTest {

    @TempDir
    Path tempDir;

    private void compile(final String code) throws IOException {
        final var srcDir = tempDir.resolve("src");
        final var javaFile = srcDir.resolve("mini/Tern.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, code);

        final var outDir = tempDir.resolve("out");
        final var rootPath = "C:\\projects\\nabu\\compiler";
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.MODULE_SOURCE_PATH, rootPath + "\\src\\test\\resources\\jmods")
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();

        final var compiler = new NabuCompiler();
        final var diagnostics = new AtomicInteger();
        final var messages = new java.util.ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                diagnostics.incrementAndGet();
                messages.add(d.getMessage(java.util.Locale.ROOT).toString());
            }
        });
        final var rc = compiler.compile(options);
        assertEquals(0, rc + diagnostics.get(),
                String.join("\n", messages));
    }

    @Test
    void intTernary() throws IOException {
        compile("package mini;\n" +
                "public class Tern {\n" +
                "    int pick(boolean flag) {\n" +
                "        int x = flag ? 1 : 2;\n" +
                "        return flag ? x : x + 1;\n" +
                "    }\n" +
                "}\n");
    }

    @Test
    void stringTernary() throws IOException {
        compile("package mini;\n" +
                "public class Tern2 {\n" +
                "    String pick(boolean flag, String name) {\n" +
                "        return flag ? name : \"world\";\n" +
                "    }\n" +
                "}\n");
    }

    @Test
    void nestedTernaryInCatch() throws IOException {
        compile("package mini;\n" +
                "public class Tern3 {\n" +
                "    interface Gen { int code(); }\n" +
                "    int run(Gen[] gens) {\n" +
                "        int result = 0;\n" +
                "        for (Gen g : gens) {\n" +
                "            try {\n" +
                "                int c = g.code();\n" +
                "                result = c;\n" +
                "            } catch (Exception ex) {\n" +
                "                result = (result == 0) ? 1 : result;\n" +
                "            }\n" +
                "        }\n" +
                "        return result;\n" +
                "    }\n" +
                "}\n");
    }

    @Test
    void ternaryAsMethodArgument() throws IOException {
        compile("package mini;\n" +
                "public class Tern3 {\n" +
                "    public static void main(String[] args) {\n" +
                "        String s = args.length > 0 ? \"a\" : \"b\";\n" +
                "        java.lang.System.out.println(s);\n" +
                "    }\n" +
                "}\n");
    }
}
