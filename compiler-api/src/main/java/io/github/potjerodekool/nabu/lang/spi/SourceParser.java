package io.github.potjerodekool.nabu.lang.spi;

import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

public interface SourceParser {

    /**
     * @return Return the source kind this parser supports.
     */
    FileObject.Kind getSourceKind();

    /**
     * @param fileObject The file object containing the source.
     * @param compilerContext The compiler context.
     * @return Return the parsed compilation unit.
     */
    CompilationUnit parse(FileObject fileObject,
                          CompilerContext compilerContext);
}
