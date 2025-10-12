package io.github.potjerodekool.nabu.compiler.extension;

import io.github.potjerodekool.nabu.compiler.resolve.spi.internal.StandardElementResolver;
import io.github.potjerodekool.nabu.lang.spi.LanguageParser;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.transform.spi.CodeTransformer;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.*;
import java.util.stream.Collectors;

public class ExtensionManager {

    private final PluginRegistry pluginRegistry;
    private final Map<String, List<PluginExtension>> extensions = new HashMap<>();

    private final Map<FileObject.Kind, LanguageParser> languageParsers = new HashMap<>();
    private final List<ElementResolver> symbolResolvers = new ArrayList<>();
    private final ElementResolver standardResolver = new StandardElementResolver();

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

    private void initLanguageParsers() {
        if (this.languageParsers.isEmpty()) {
            final List<LanguageParser> languageParsers = this.pluginRegistry.createExtensions(
                    true,
                    "language-parser",
                    LanguageParser.class,
                    compilerContext
            );

            this.languageParsers.putAll(
                    languageParsers.stream()
                            .collect(Collectors.toMap(
                                    LanguageParser::getSourceKind,
                                    it -> it
                            )));
        }
    }

    public Collection<LanguageParser> getLanguageParsers() {
        this.initLanguageParsers();
        return this.languageParsers.values();
    }

    public Optional<LanguageParser> getLanguageParser(final FileObject.Kind kind) {
        this.initLanguageParsers();
        return Optional.ofNullable(this.languageParsers.get(kind));
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
        return this.symbolResolvers.stream()
                .filter(resolver -> resolver.supports(searchType, compilerContext, scope))
                .findFirst()
                .or(() -> Optional.of(this.standardResolver));
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
