package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuCFileManager;

public abstract class AbstractCompilerTest {

    private final CompilerContextImpl compilerContext = createCompilerContext();

    private PluginRegistry createPluginRegistry() {
        final var pluginRegistry = new PluginRegistry();
        pluginRegistry.registerPlugins(compilerContext);
        return pluginRegistry;
    }

    private CompilerContextImpl createCompilerContext() {
        final var fileManager = new NabuCFileManager();
        final var pluginRegistry = createPluginRegistry();

        final var compilerContext = new CompilerContextImpl(
                fileManager,
                pluginRegistry
        );

        fileManager.initialize(pluginRegistry);

        return compilerContext;
    }

    protected CompilerContextImpl getCompilerContext() {
        return compilerContext;
    }
}
