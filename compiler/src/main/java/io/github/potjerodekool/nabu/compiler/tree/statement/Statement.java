package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

public abstract class Statement extends Tree {

    public Statement() {
    }

    public Statement(final StatementBuilder<?> builder) {
        super(builder);
    }

}
