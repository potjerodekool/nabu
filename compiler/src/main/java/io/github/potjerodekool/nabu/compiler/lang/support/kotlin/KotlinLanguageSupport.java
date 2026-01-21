package io.github.potjerodekool.nabu.compiler.lang.support.kotlin;

import io.github.potjerodekool.nabu.lang.spi.LanguageSupport;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

import java.io.IOException;

public class KotlinLanguageSupport implements LanguageSupport {

    private final Logger logger = Logger.getLogger(getClass().getName());

    private final FileObject.Kind KOTLIN_KIND = new FileObject.Kind(".kt", true);

    @Override
    public FileObject.Kind getSourceKind() {
        return KOTLIN_KIND;
    }

    @Override
    public CompilationUnit parse(final FileObject fileObject,
                                 final CompilerContext compilerContext) {
        logger.log(LogLevel.INFO, "Parsing " + fileObject.getFileName());

        try (var inputStream = fileObject.openInputStream()) {
            final var compilationUnitContext = KotlinCompilerParser.parse(inputStream);
            final var visitor = new KotlinCompilerVisitor(fileObject);
            return (CompilationUnit) compilationUnitContext.accept(visitor);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
