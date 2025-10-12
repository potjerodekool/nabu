package io.github.potjerodekool.nabu.lang.spi;

import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

public interface LanguageParser {

    FileObject.Kind getSourceKind();

    CompilationUnit parse(FileObject fileObject,
                          CompilerContext compilerContext);
}