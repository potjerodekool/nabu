package io.github.potjerodekool.nabu.tools.transform.spi;

import io.github.potjerodekool.nabu.tree.CompilationUnit;

/**
 * A transformer to transform a compilation unit.
 */
public interface CodeTransformer {

    /**
     * @param compilationUnit The compilation unit to transform.
     */
    void transform(CompilationUnit compilationUnit);
}
