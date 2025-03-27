package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CFieldAccessExpressionTree;

public class FieldAccessExpressionBuilder extends ExpressionBuilder<FieldAccessExpressionTree, FieldAccessExpressionBuilder> {

    private ExpressionTree target;
    private ExpressionTree field;

    public FieldAccessExpressionBuilder() {
        super();
    }

    public FieldAccessExpressionBuilder(final FieldAccessExpressionTree fieldAccessExpressionTree) {
        super(fieldAccessExpressionTree);
        this.target = fieldAccessExpressionTree.getTarget();
        this.field = fieldAccessExpressionTree.getField();
    }

    @Override
    public FieldAccessExpressionBuilder self() {
        return this;
    }

    public ExpressionTree getTarget() {
        return target;
    }

    public ExpressionTree getField() {
        return field;
    }

    public FieldAccessExpressionBuilder target(final ExpressionTree target) {
        this.target = target;
        return this;
    }

    public FieldAccessExpressionBuilder field(final ExpressionTree field) {
        this.field = field;
        return this;
    }

    @Override
    public FieldAccessExpressionTree build() {
        return new CFieldAccessExpressionTree(this);
    }
}
