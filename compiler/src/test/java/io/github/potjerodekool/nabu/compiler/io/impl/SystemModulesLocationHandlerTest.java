package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemModulesLocationHandlerTest {

    private static Set<String> moduleNames(final SystemModulesLocationHandler handler) throws Exception {
        final Set<String> names = new HashSet<>();
        try (handler) {
            for (final var locations : handler.listLocationsForModules()) {
                for (final var location : locations) {
                    names.add(((ModuleLocationHandler) location).getModuleName());
                }
            }
        }
        return names;
    }

    @Test
    void withoutSystemOptionTheModulesOfTheRunningJdk() throws Exception {
        final var names = moduleNames(new SystemModulesLocationHandler());
        assertTrue(names.stream().anyMatch(name -> name.equals("java.base")), names.toString());
    }

    /** A SYSTEM path without lib/modules (a missing or empty directory) used to leave no modules at all. */
    @Test
    void aSystemPathThatIsNoJavaHomeFallsBackToTheRunningJdk(@TempDir final Path notAJavaHome) throws Exception {
        final var handler = new SystemModulesLocationHandler();
        handler.processOption(CompilerOption.SYSTEM, notAJavaHome.toString());
        final var names = moduleNames(handler);
        assertTrue(names.stream().anyMatch(name -> name.equals("java.base")), names.toString());
    }

    @Test
    void aSystemPathThatDoesNotExistFallsBackToTheRunningJdk(@TempDir final Path parent) throws Exception {
        final var handler = new SystemModulesLocationHandler();
        handler.processOption(CompilerOption.SYSTEM, parent.resolve("no-such-jdk").toString());
        final var names = moduleNames(handler);
        assertTrue(names.stream().anyMatch(name -> name.equals("java.base")), names.toString());
    }
}
