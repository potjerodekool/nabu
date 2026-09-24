package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Minimal-reproductie: try/catch met int-flow binnen enhanced-for (picocli resolveExitCode-shape). */
class TryCatchLoopShapeTest {

    @TempDir
    Path tempDir;

    private int compile(String code) throws IOException {
        final var srcDir = tempDir.resolve("src");
        final var javaFile = srcDir.resolve("mini/Mini.java");
        Files.createDirectories(javaFile.getParent());
        try {
            Files.writeString(javaFile, code);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        return diagnostics.get();
    }

    @Test
    void tryCatchInsideLoop() throws IOException {
        compile("package mini;\n" +
                "public class Mini {\n" +
                "    interface Gen { int code(); }\n" +
                "    interface Namer { String describe(); }\n" +
                "    int run(Gen[] gens, Namer namer) {\n" +
                "        int result = 0;\n" +
                "        for (Gen g : gens) {\n" +
                "            try {\n" +
                "                int c = g.code();\n" +
                "                if ((c > 0 && c > result) || (c < result && result <= 0)) {\n" +
                "                    result = c;\n" +
                "                }\n" +
                "            } catch (Exception ex) {\n" +
                "                result = (result == 0) ? 1 : result;\n" +
                "                ex.printStackTrace();\n" +
                "            }\n" +
                "        }\n" +
                "        namer.describe();\n" +
                "        if (namer instanceof Gen) {\n" +
                "            Gen g = (Gen) namer;\n" +
                "        }\n" +
                "        return result == 0 ? 2 : result;\n" +
                "    }\n" +
                "}\n");
    }

    @Test
    void resolveExitCodeShape() throws IOException {
        // 1:1-vorm van picocli CommandLine$AbstractParseResultHandler.resolveExitCode
        // (generieke klasse <R>, List-generic + type-variable cast)
        compile("package mini;\n" +
                "public class Mini3<R> {\n" +
                "    interface Gen { int code(); }\n" +
                "    int resolve(int exitCodeOnSuccess, R executionResult, java.util.List<Gen> gens) {\n" +
                "        int result = 0;\n" +
                "        for (Gen generator : gens) {\n" +
                "            try {\n" +
                "                int exitCode = generator.code();\n" +
                "                if ((exitCode > 0 && exitCode > result) || (exitCode < result && result <= 0)) {\n" +
                "                    result = exitCode;\n" +
                "                }\n" +
                "            } catch (Exception ex) {\n" +
                "                result = (result == 0) ? 1 : result;\n" +
                "                ex.printStackTrace();\n" +
                "            }\n" +
                "        }\n" +
                "        if (executionResult instanceof java.util.List) {\n" +
                "            java.util.List<?> resultList = (java.util.List<?>) executionResult;\n" +
                "            for (Object obj : resultList) {\n" +
                "                if (obj instanceof Integer) {\n" +
                "                    int exitCode2 = (Integer) obj;\n" +
                "                    if ((exitCode2 > 0 && exitCode2 > result) || (exitCode2 < result && result <= 0)) {\n" +
                "                        result = exitCode2;\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "        return result == 0 ? exitCodeOnSuccess : result;\n" +
                "    }\n" +
                "}\n");
    }

    @Test
    void loopOverListWithInstanceOfInt() throws IOException {
        compile("package mini;\n" +
                "public class Mini2 {\n" +
                "    interface Gen { int code(); }\n" +
                "    int scan(java.util.List<Object> results, int deflt, Gen g) {\n" +
                "        int result = 0;\n" +
                "        for (Object o : results) {\n" +
                "            try {\n" +
                "                int c = g.code();\n" +
                "                if ((c > 0 && c > result) || (c < result && result <= 0)) {\n" +
                "                    result = c;\n" +
                "                }\n" +
                "            } catch (Exception ex) {\n" +
                "                result = (result == 0) ? 1 : result;\n" +
                "                ex.printStackTrace();\n" +
                "            }\n" +
                "        }\n" +
                "        if (results instanceof java.util.List<?>) {\n" +
                "            for (Object o : results) {\n" +
                "                if (o instanceof Integer) {\n" +
                "                    int exitCode = (Integer) o;\n" +
                "                    if ((exitCode > 0 && exitCode > result) || (exitCode < result && result <= 0)) {\n" +
                "                        result = exitCode;\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "        java.lang.System.out.println(\"k\" + result);\n" +
                "        return result == 0 ? deflt : result;\n" +
                "    }\n" +
                "}\n");
    }
}

