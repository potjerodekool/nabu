package io.github.potjerodekool.nabu.compiler.extension;

import io.github.potjerodekool.nabu.lang.spi.LanguageParser;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;

import java.util.*;

public class LanguageParserManager {

    private final PluginRegistry pluginRegistry;
    private CompilerContext compilerContext;

    private final Map<FileObject.Kind, LanguageParser> languageParsers = new HashMap<>();
    private final List<FileObject.Kind> sourceKinds = new ArrayList<>();

    public LanguageParserManager(final PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    public void init(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    public Collection<LanguageParser> getLanguageParsers() {
        this.initLanguageParsers();
        return this.languageParsers.values();
    }

    public Optional<LanguageParser> getLanguageParser(final FileObject.Kind kind) {
        this.initLanguageParsers();
        return Optional.ofNullable(this.languageParsers.get(kind));
    }

    public Collection<FileObject.Kind> getSourceKinds() {
        getLanguageParsers();
        return this.sourceKinds;
    }

    private void initLanguageParsers() {
        if (this.languageParsers.isEmpty()) {
            this.pluginRegistry.getExtensions("language-parser").forEach(extension -> {
                final var languageParser = extension.createExtension(LanguageParser.class, true, compilerContext);
                final var sourceKind = languageParser.getSourceKind();
                this.languageParsers.put(sourceKind, languageParser);
                this.sourceKinds.add(sourceKind);
            });
        }
    }
}
