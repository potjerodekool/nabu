package io.github.potjerodekool.nabu.tree.expression.impl;


import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.TypeNameExpressionTree;

/**
 * Implementation of TypeNameExpressionTree.
 */
public class CTypeNameExpressionTree extends CExpressionTree implements TypeNameExpressionTree {

    private final ExpressionTree packageName;
    private final ExpressionTree idenifier;

    public CTypeNameExpressionTree(final ExpressionTree packageName,
                                   final ExpressionTree idenifier,
                                   final int lineNumber,
                                   final int columnNumber) {
        super(lineNumber, columnNumber);
        this.packageName = packageName;
        this.idenifier = idenifier;
    }

    public ExpressionTree getPackageName() {
        return packageName;
    }

    public ExpressionTree getIdentifier() {
        return idenifier;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeNameExpression(this, param);
    }
}
