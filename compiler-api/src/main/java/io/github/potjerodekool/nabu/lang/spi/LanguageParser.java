package io.github.potjerodekool.nabu.lang.spi;

import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

/**
 * A parser for a language file.
 * This interface can be implemented to support languages.
 */
public interface LanguageParser {

    /**
     * @return Return the source kind this parser supports.
     */
    FileObject.Kind getSourceKind();

    /**
     * @param fileObject The file object containing the source.
     * @param moduleElement The module element of the given file.
     * @param compilerContext The compiler context.
     * @return Return the parsed compilation unit.
     */
    CompilationUnit parse(FileObject fileObject,
                          ModuleElement moduleElement,
                          CompilerContext compilerContext);
}