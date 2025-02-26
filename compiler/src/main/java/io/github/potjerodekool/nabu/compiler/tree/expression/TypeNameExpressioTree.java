package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class TypeNameExpressioTree extends ExpressionTree {

    private final ExpressionTree packageName;
    private final ExpressionTree idenifier;

    public TypeNameExpressioTree(final ExpressionTree packageName,
                                 final ExpressionTree idenifier) {
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
