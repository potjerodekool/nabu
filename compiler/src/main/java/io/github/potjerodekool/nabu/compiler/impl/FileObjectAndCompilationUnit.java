package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

public record FileObjectAndCompilationUnit(FileObject fileObject,
                                           CompilationUnit compilationUnit) {
}
