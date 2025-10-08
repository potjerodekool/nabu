package io.github.potjerodekool.nabu.compiler.tree.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.Flags;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.RequiresTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

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
