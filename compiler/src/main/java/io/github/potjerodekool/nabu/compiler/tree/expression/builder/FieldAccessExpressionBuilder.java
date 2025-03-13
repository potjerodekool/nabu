package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.backend.ir.expression.Ex;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CFieldAccessExpressionTree;

public class FieldAccessExpressionBuilder extends ExpressionBuilder<FieldAccessExpressionTree> {

    private ExpressionTree target;
    private ExpressionTree field;

    public FieldAccessExpressionBuilder(final FieldAccessExpressionTree fieldAccessExpressionTree) {
        super(fieldAccessExpressionTree);
        this.target = fieldAccessExpressionTree.getTarget();
        this.field = fieldAccessExpressionTree.getField();
    }

    @Override
    public ExpressionBuilder<FieldAccessExpressionTree> self() {
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
