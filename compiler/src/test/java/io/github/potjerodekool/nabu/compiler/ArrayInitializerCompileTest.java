package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Array-initializers (met en zonder {@code new}) en multidimensionale
 * array-types end-to-end via de officiële
 * NabuCompiler.compile-pipeline (ASM-backend): parse -> resolve -> lower ->
 * IR -> bytecode-validatie.
 */
class ArrayInitializerCompileTest {

    @TempDir
    Path tempDir;

    private void compile(final String code) throws IOException {
        final var srcDir = tempDir.resolve("src");
        final var className = code.replaceAll("(?s).*public class (\\w+).*", "$1");
        final var javaFile = srcDir.resolve("mini/" + className + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, code);

        final var outDir = tempDir.resolve("out");
        final var rootPath = "C:\\projects\\nabu\\compiler";
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
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
    void bareIntArrayInitializer() throws IOException {
        compile("package mini;\n" +
                "public class Arr1 {\n" +
                "    int sum() {\n" +
                "        int[] local = { 1, 2, 3 };\n" +
                "        return local[0];\n" +
                "    }\n" +
                "}\n");
    }

    @Test
    void bareArrayInitializer() throws IOException {
        compile("package mini;\n" +
                "public class Arr2 {\n" +
                "    int sum(boolean[] flags) {\n" +
                "        boolean[] local = { flags[0] };\n" +
                "        int t = 0;\n" +
                "        for (boolean b : local) {\n" +
                "            if (b) { t = t + 1; }\n" +
                "        }\n" +
                "        return t;\n" +
                "    }\n" +
                "}\n");
    }

    @Test
    void explicitNewArray() throws IOException {
        compile("package mini;\n" +
                "public class Arr3 {\n" +
                "    int sum(boolean[] flags) {\n" +
                "        boolean[] local = new boolean[] { flags[0] };\n" +
                "        int t = 0;\n" +
                "        for (boolean b : local) {\n" +
                "            if (b) { t = t + 1; }\n" +
                "        }\n" +
                "        return t;\n" +
                "    }\n" +
                "}\n");
    }

    @Test
    void twoDimensionalArrayOfNestedType() throws IOException {
        compile("package mini;\n" +
                "public class Arr4 {\n" +
                "    static class Text { int length; }\n" +
                "    interface Renderer { Text[][] render(); }\n" +
                "    int calc(Renderer r) {\n" +
                "        int max = 0;\n" +
                "        Text[][] values = r.render();\n" +
                "        int len = values[0][3].length;\n" +
                "        if (len < 10) { max = Math.max(max, len); }\n" +
                "        return max;\n" +
                "    }\n" +
                "}\n");
    }
}
