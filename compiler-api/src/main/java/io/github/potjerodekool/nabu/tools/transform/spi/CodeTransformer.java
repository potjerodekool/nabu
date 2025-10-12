package io.github.potjerodekool.nabu.tools.transform.spi;

import io.github.potjerodekool.nabu.tree.CompilationUnit;

public interface CodeTransformer {

    void tranform(CompilationUnit compilationUnit);
}
