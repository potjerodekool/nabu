package io.github.potjerodekool.nabu.compiler.extension;

import io.github.potjerodekool.nabu.compiler.backend.generate.ByteCodeGenerator;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.tools.CompilerConfigurationException;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.transform.spi.CodeTransformer;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.*;

public class ExtensionManager {

    private final PluginRegistry pluginRegistry;
    private final Map<String, List<PluginExtension>> extensions = new HashMap<>();
    private final List<ElementResolver> symbolResolvers = new ArrayList<>();

    private CompilerContext compilerContext;

    public ExtensionManager(final PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    public void init(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    public void registerExtension(final String name, final PluginExtension pluginExtension) {
        this.extensions.computeIfAbsent(name, key -> new ArrayList<>()).add(pluginExtension);
    }

    public List<PluginExtension> getExtensions(final String name) {
        return this.extensions.getOrDefault(name, List.of());
    }

    private int getRuntimeFeatureVersion() {
        return Runtime.version().feature();
    }

    public ByteCodeGenerator createByteCodeGenerator() {
        final var runtimeFeatureVersion = getRuntimeFeatureVersion();
        final var extensionOptional = getExtensions("bytecode-generator").stream()
                .filter(extension -> extension.supportsJdkFeatureVersion(runtimeFeatureVersion))
                .findFirst();
        return extensionOptional
                .map(extension -> extension.createExtension(ByteCodeGenerator.class, false, compilerContext))
                .orElseThrow(() -> new CompilerConfigurationException("No bytecode generator available"));
    }

    private void initSymbolResolvers() {
        if (this.symbolResolvers.isEmpty()) {
            this.symbolResolvers.addAll(
                    this.pluginRegistry.createExtensions(
                            true,
                            "element-resolver",
                            ElementResolver.class,
                            compilerContext
                    )
            );
        }

    }

    public Optional<ElementResolver> findSymbolResolver(final TypeMirror searchType,
                                                        final Scope scope) {
        this.initSymbolResolvers();
        /*
        return this.symbolResolvers.stream()
                .filter(resolver -> resolver.supports(searchType, compilerContext, scope))
                .findFirst()
                .or(() -> Optional.of(this.standardResolver));
        */
        return Optional.empty();
    }

    public List<CodeTransformer> getCodeTransformers() {
        return this.pluginRegistry.createExtensions(
                true,
                "code-transformer",
                CodeTransformer.class,
                compilerContext
        );
    }
}
