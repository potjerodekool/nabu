package io.github.potjerodekool.nabu.compiler.lang.support.java;

import io.github.potjerodekool.nabu.lang.spi.LanguageParser;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.compiler.resolve.internal.EnterClasses;
import io.github.potjerodekool.nabu.compiler.resolve.internal.java.JavaSourceEnterClasses;
import io.github.potjerodekool.nabu.tree.CompilationUnit;


public class JavaLanguageParser implements LanguageParser {

    private final FileObject.Kind JAVA_KIND = new FileObject.Kind(".java", true);

    @Override
    public FileObject.Kind getSourceKind() {
        return JAVA_KIND;
    }

    @Override
    public CompilationUnit parse(final FileObject fileObject,
                                 final CompilerContext compilerContext) {
        final var enterClasses = new EnterClasses((CompilerContextImpl) compilerContext);

        final var sourceEnterClasses = new JavaSourceEnterClasses(enterClasses);
        return sourceEnterClasses.parse(fileObject);
    }
}
