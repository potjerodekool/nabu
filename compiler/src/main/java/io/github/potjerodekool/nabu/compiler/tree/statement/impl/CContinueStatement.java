package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.statement.ContinueStatement;

public class CContinueStatement extends CStatementTree implements ContinueStatement {

    private final Tree target;

    public CContinueStatement(final Tree target,
                              final int lineNumber, final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.target = target;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitContinueStatement(this, param);
    }

    @Override
    public Tree getTarget() {
        return target;
    }
}
