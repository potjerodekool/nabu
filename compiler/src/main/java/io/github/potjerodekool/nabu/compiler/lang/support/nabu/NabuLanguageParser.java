package io.github.potjerodekool.nabu.compiler.lang.support.nabu;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.frontend.parser.nabu.NabuCompilerParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.nabu.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.lang.support.LanguageParser;
import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

import java.io.IOException;

public class NabuLanguageParser implements LanguageParser {

    private final Logger logger = Logger.getLogger(NabuLanguageParser.class.getName());

    private final FileObject.Kind sourceKind = new FileObject.Kind(".nabu", true);

    @Override
    public FileObject.Kind getSourceKind() {
        return sourceKind;
    }

    @Override
    public CompilationUnit parse(final FileObject fileObject,
                                 final CompilerContext compilerContext) {
        logger.log(LogLevel.INFO, "Parsing " + fileObject.getFileName());

        try (var inputStream = fileObject.openInputStream()) {
            final var compilationUnitContext = NabuCompilerParser.parse(inputStream);
            final var visitor = new NabuCompilerVisitor(fileObject);
            return (CompilationUnit) compilationUnitContext.accept(visitor);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
