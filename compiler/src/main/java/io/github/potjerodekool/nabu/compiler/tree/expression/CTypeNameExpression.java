package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class CTypeNameExpression extends CExpression {

    private final CExpression packageName;
    private final CExpression idenifier;

    public CTypeNameExpression(final CExpression packageName,
                               final CExpression idenifier) {
        this.packageName = packageName;
        this.idenifier = idenifier;
    }

    public CExpression getPackageName() {
        return packageName;
    }

    public CExpression getIdenifier() {
        return idenifier;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeNameExpression(this, param);
    }
}
