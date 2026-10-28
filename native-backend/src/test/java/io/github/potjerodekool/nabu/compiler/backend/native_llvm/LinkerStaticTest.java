package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** On Windows (MinGW) the runtime of the toolchain is linked into the executable, unless it is switched off. */
class LinkerStaticTest {

    private String saved;

    @BeforeEach
    void save() {
        saved = System.getProperty("nabu.link.static");
        System.clearProperty("nabu.link.static");
    }

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty("nabu.link.static");
        } else {
            System.setProperty("nabu.link.static", saved);
        }
    }

    private static List<String> mingwCommand() throws Exception {
        return Linker.buildCommand(List.of(Path.of("a.o")), Path.of("a.exe"), "x86_64-w64-windows-gnu",
                CompileOptions.GcStrategy.BOEHM, List.of(Path.of("rt.o")));
    }

    @Test
    void theMinGwRuntimeIsLinkedInByDefault() throws Exception {
        assertTrue(mingwCommand().contains("-static"), mingwCommand().toString());
    }

    @Test
    void itCanBeSwitchedOff() throws Exception {
        System.setProperty("nabu.link.static", "false");

        assertFalse(mingwCommand().contains("-static"), mingwCommand().toString());
    }

    @Test
    void itCanBeSwitchedOnExplicitly() throws Exception {
        System.setProperty("nabu.link.static", "true");

        assertTrue(mingwCommand().contains("-static"), mingwCommand().toString());
    }

    @Test
    void otherPlatformsAreNotAffected() throws Exception {
        final List<String> command = Linker.buildCommand(List.of(Path.of("a.o")), Path.of("a"), "x86_64-pc-linux-gnu",
                CompileOptions.GcStrategy.BOEHM, List.of(Path.of("rt.o")));

        assertFalse(command.contains("-static"), command.toString());
    }
}
