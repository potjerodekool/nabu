package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.statement.BreakStatement;

public class CBreakStatement extends CStatementTree implements BreakStatement {

    private final Tree target;

    public CBreakStatement(final Tree target,
                           final int lineNumber, final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.target = target;
    }

    @Override
    public Tree getTarget() {
        return target;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitBreakStatement(this, param);
    }
}
