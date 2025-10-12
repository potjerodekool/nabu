package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.tree.CatchTree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.builder.CatchTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

public class CCatchTree extends CTree implements CatchTree {

    private final VariableDeclaratorTree variable;
    private final BlockStatementTree body;

    public CCatchTree(final VariableDeclaratorTree variable,
                      final BlockStatementTree body,
                      final int lineNumber,
                      final int columnNumber) {
        super(lineNumber, columnNumber);
        this.variable = variable;
        this.body = body;
    }

    public CCatchTree(final CatchTreeBuilder catchTreeBuilder) {
        super(catchTreeBuilder);
        this.variable = catchTreeBuilder.getVariable();
        this.body = catchTreeBuilder.getBody();
    }

    @Override
    public VariableDeclaratorTree getVariable() {
        return variable;
    }

    @Override
    public BlockStatementTree getBody() {
        return body;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitCatch(this, param);
    }
}
