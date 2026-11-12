package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debug info is off by default: the C runtime is only compiled with {@code -g} when it is switched on. The line
 * tables of the compiled program itself come from the backend's object files and are not affected by this flag.
 */
class LinkerDebugInfoTest {

    private static List<String> command(final String triple, final boolean debugInfo) throws Exception {
        return Linker.buildCommand(List.of(Path.of("a.o")), Path.of("a"), triple,
                CompileOptions.GcStrategy.BOEHM, List.of(Path.of("rt.o")), debugInfo);
    }

    @Test
    void noDebugFlagWithoutDebugInfo() throws Exception {
        assertFalse(command("x86_64-pc-linux-gnu", false).contains("-g"),
                command("x86_64-pc-linux-gnu", false).toString());
        assertFalse(command("x86_64-w64-windows-gnu", false).contains("-g"),
                command("x86_64-w64-windows-gnu", false).toString());
        assertFalse(command("arm64-apple-macosx", false).contains("-g"),
                command("arm64-apple-macosx", false).toString());
    }

    @Test
    void debugFlagWithDebugInfo() throws Exception {
        assertTrue(command("x86_64-pc-linux-gnu", true).contains("-g"));
        assertTrue(command("arm64-apple-macosx", true).contains("-g"));
        assertTrue(command("x86_64-w64-windows-gnu", true).contains("-g"));
        assertTrue(command("x86_64-w64-windows-gnu", true).contains("-gdwarf-4"));
    }
}
