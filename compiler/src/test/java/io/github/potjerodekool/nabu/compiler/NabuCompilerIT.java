package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NabuCompilerIT {

    @Test
    void compile() {
        final var optionBuilder = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, "C:\\Users\\evert\\IdeaProjects\\test-app\\src\\nabu");

        final var compiler = new NabuCompiler();
        compiler.compile(optionBuilder.build());
    }
}