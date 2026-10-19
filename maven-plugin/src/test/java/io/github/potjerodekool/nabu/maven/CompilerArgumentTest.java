package io.github.potjerodekool.nabu.maven;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * compilerArgs names map to the compiler's options. `--system` had a leading
 * space in its option name, so `--system:dir` became an unknown option.
 */
class CompilerArgumentTest {

    @Test
    void namesWithOrWithoutDashesMapToKnownOptions() {
        assertSame(CompilerOption.SYSTEM, AbstractCompilerMojo.create("--system"));
        assertSame(CompilerOption.SYSTEM, AbstractCompilerMojo.create("system"));
        assertSame(CompilerOption.BACKEND, AbstractCompilerMojo.create("--backend"));
        assertSame(CompilerOption.CLASS_OUTPUT, AbstractCompilerMojo.create("-d"));
        assertSame(CompilerOption.BYTECODE_JDK_HOME, AbstractCompilerMojo.create("--bytecode-jdk-home"));
    }
}
