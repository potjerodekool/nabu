package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.impl.*;
import io.github.potjerodekool.nabu.tools.*;

import java.nio.file.*;
import java.util.*;

public abstract class AbstractCompilerTest {

    private final CompilerContextImpl compilerContext = createCompilerContext();

    private CompilerContextImpl createCompilerContext() {
        final var fileManager = new NabuCFileManager();

        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, "src/test/resources")
                .option(CompilerOption.SOURCE_PATH, "src/test/resources/classes")
                .option(CompilerOption.MODULE_SOURCE_PATH, "src/test/resources/jmods")
                .build();
        fileManager.processOptions(options);

        return new CompilerContextImpl(fileManager);
    }

    protected CompilerContextImpl getCompilerContext() {
        return compilerContext;
    }

    protected String loadResource(final String resourceName) {
        try (var resource = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (resource == null) {
                throw new NullPointerException("Failed to load resource " + resourceName);
            }
            return new String(resource.readAllBytes());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}
