package io.github.potjerodekool.nabu.compiler.lang.support.java;

import io.github.potjerodekool.nabu.lang.spi.LanguageSupport;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

import java.io.IOException;


public class JavaLanguageSupport implements LanguageSupport {

    private final Logger logger = Logger.getLogger(getClass().getName());

    private final FileObject.Kind JAVA_KIND = new FileObject.Kind(".java", true);

    @Override
    public FileObject.Kind getSourceKind() {
        return JAVA_KIND;
    }

    @Override
    public CompilationUnit parse(final FileObject fileObject,
                                 final CompilerContext compilerContext) {
        logger.log(LogLevel.INFO, "Parsing " + fileObject.getFileName());

        try (var inputStream = fileObject.openInputStream()) {
            final var compilationUnitContext = JavaCompilerParser.parse(inputStream);
            final var visitor = new JavaCompilerVisitor(fileObject);
            return (CompilationUnit) compilationUnitContext.accept(visitor);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
