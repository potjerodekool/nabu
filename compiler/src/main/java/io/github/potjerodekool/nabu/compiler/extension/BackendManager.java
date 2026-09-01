package io.github.potjerodekool.nabu.compiler.extension;

import io.github.potjerodekool.nabu.backend.Backend;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.util.CompileException;

public class BackendManager {

    public static Backend createBackend(final String backendName,
                                        final PluginRegistry pluginRegistry,
                                        final CompilerContext compilerContext) throws CompileException {

        final var extensionOptional = pluginRegistry.getExtensions("backend").stream()
                .filter(extension -> backendName.equals(extension.getAttribute("name")))
                .findFirst();

        if (extensionOptional.isEmpty()) {
            throw new CompileException("Backend not found %s".formatted(backendName));
        }

        final var extension = extensionOptional.get();
        return pluginRegistry.createExtension(extension, Backend.class, compilerContext);
    }
}
