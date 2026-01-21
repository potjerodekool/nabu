package io.github.potjerodekool.nabu.compiler.extension;

import io.github.potjerodekool.nabu.lang.spi.LanguageSupport;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;

import java.util.*;

public class LanguageSupportManager {

    private final PluginRegistry pluginRegistry;
    private CompilerContext compilerContext;
    private final Map<FileObject.Kind, LanguageSupport> languageSupporters = new HashMap<>();
    private final List<FileObject.Kind> sourceKinds = new ArrayList<>();

    public LanguageSupportManager(final PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    public void init(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    public Optional<LanguageSupport> getLanguageSupporter(final FileObject.Kind kind) {
        this.initLanguageSupporters();
        return Optional.ofNullable(this.languageSupporters.get(kind));
    }

    private void initLanguageSupporters() {
        if (this.languageSupporters.isEmpty()) {
            this.pluginRegistry.getExtensions("language-support").forEach(extension -> {
                final var languageSupporter = extension.createExtension(LanguageSupport.class, true, compilerContext);
                final var sourceKind = languageSupporter.getSourceKind();
                this.languageSupporters.put(sourceKind, languageSupporter);
                this.sourceKinds.add(sourceKind);
            });
        }
    }

    public Collection<FileObject.Kind> getSourceKinds() {
        initLanguageSupporters();
        return sourceKinds;
    }
}
