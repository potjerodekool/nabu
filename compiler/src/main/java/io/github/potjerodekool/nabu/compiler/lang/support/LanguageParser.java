package io.github.potjerodekool.nabu.compiler.lang.support;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

public interface LanguageParser {

    FileObject.Kind getSourceKind();

    CompilationUnit parse(FileObject fileObject,
                          CompilerContext compilerContext);
}
