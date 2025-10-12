package io.github.potjerodekool.nabu.tree.element.impl;

import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.element.RequiresTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public class CRequiresTree extends CDirective implements RequiresTree {

    private final long flags;
    private final ExpressionTree moduleName;

    public CRequiresTree(final long flags,
                         final ExpressionTree moduleName,
                         final int lineNumber,
                         final int columnNumber) {
        super(lineNumber, columnNumber);
        this.flags = flags;
        this.moduleName = moduleName;
    }

    @Override
    public boolean isStatic() {
        return Flags.hasFlag(flags, Flags.STATIC);
    }

    @Override
    public boolean isTransitive() {
        return Flags.hasFlag(flags, Flags.TRANSITIVE);
    }

    @Override
    public ExpressionTree getModuleName() {
        return moduleName;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitRequires(this, param);
    }
}
