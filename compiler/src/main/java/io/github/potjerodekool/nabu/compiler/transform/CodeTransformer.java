package io.github.potjerodekool.nabu.compiler.transform;

import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

public interface CodeTransformer {

    void tranform(CompilationUnit compilationUnit);
}
