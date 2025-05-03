package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

public record FileObjectAndCompilationUnit(FileObject fileObject,
                                           CompilationUnit compilationUnit) {
}
