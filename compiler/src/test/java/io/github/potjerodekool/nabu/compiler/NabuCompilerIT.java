package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Disabled
class NabuCompilerIT {

    @Test
    void compile() {
        // Externe smoke-test op een zelfstandig project: het pad is daarom
        // configureerbaar en de test slaat zichzelf over als het ontbreekt.
        final var sourcePath = Path.of(System.getProperty(
                "nabu.it.sourcePath", "src/test/resources/classes"));
        Assumptions.assumeTrue(Files.isDirectory(sourcePath),
                "source path niet gevonden: " + sourcePath
                        + " (zet -Dnabu.it.sourcePath=<pad>)");

        final var optionBuilder = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, sourcePath.toString());

        final var compiler = new NabuCompiler();
        compiler.compile(optionBuilder.build());
    }
}