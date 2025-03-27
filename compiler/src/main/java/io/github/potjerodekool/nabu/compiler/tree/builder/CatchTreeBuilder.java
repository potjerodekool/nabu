package io.github.potjerodekool.nabu.compiler.tree.builder;

import io.github.potjerodekool.nabu.compiler.tree.CatchTree;
import io.github.potjerodekool.nabu.compiler.tree.impl.CCatchTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;

public class CatchTreeBuilder extends TreeBuilder<CatchTree, CatchTreeBuilder> {

    private VariableDeclaratorTree variable;
    private BlockStatementTree body;

    @Override
    public CatchTreeBuilder self() {
        return this;
    }

    public VariableDeclaratorTree getVariable() {
        return variable;
    }

    public CatchTreeBuilder variable(final VariableDeclaratorTree variable) {
        this.variable = variable;
        return this;
    }

    public BlockStatementTree getBody() {
        return body;
    }

    public CatchTreeBuilder body(final BlockStatementTree body) {
        this.body = body;
        return this;
    }

    @Override
    public CatchTree build() {
        return new CCatchTree(this);
    }
}
