package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.TypeNameExpressionTree;

public class CTypeNameExpressionTree extends CExpressionTree implements TypeNameExpressionTree {

    private final ExpressionTree packageName;
    private final ExpressionTree idenifier;

    public CTypeNameExpressionTree(final ExpressionTree packageName,
                                   final ExpressionTree idenifier,
                                   final int lineNumber,
                                   final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.packageName = packageName;
        this.idenifier = idenifier;
    }

    public ExpressionTree getPackageName() {
        return packageName;
    }

    public ExpressionTree getIdenifier() {
        return idenifier;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeNameExpression(this, param);
    }
}
