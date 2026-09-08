package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.resolve.scope.NamedImportScope;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Maakt de import-gedrag voor GENESTE klassen expliciet: de geïmporteerde
 * "picocli.CommandLine.Command" wordt in de named import scope gedefinieerd
 * onder de qualified name van de klasse. Voor een geneste klasse is dat een
 * dollar-naam ("picocli.CommandLine$Command"). De resolutie van de simple
 * naam "Command" moet die key ook kunnen vinden.
 */
class NamedImportScopeNestedTypeTest extends AbstractCompilerTest {

    @Override
    protected String getClassPath() {
        return createClassPath(getLocationOfClass(picocli.CommandLine.class));
    }

    @Test
    void nestedTypeIsDefinedUnderDollarKey() {
        final var loader = getCompilerContext().getClassElementLoader();
        final var command = loader.loadClass(getUnnamedModule(), "picocli.CommandLine.Command");

        assertNotNull(command, "picocli.CommandLine.Command moet laden");
        assertEquals("picocli.CommandLine$Command", command.getQualifiedName(),
                "geneste klasse heeft dollar-scheiding in qualified name");

        final var importScope = new NamedImportScope();
        importScope.define(command);

        assertNotNull(importScope.resolve("Command"),
                "resolve('Command') moet de geneste import vinden");
        assertNotNull(importScope.resolveType("Command"),
                "resolveType('Command') moet de geneste import vinden");
    }
}