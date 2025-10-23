package io.github.potjerodekool.nabu.tree.expression.builder;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;

public class FieldAccessExpressionBuilder extends ExpressionBuilder<FieldAccessExpressionTree, FieldAccessExpressionBuilder> {

    private ExpressionTree selected;
    private IdentifierTree field;

    public FieldAccessExpressionBuilder() {
        super();
    }

    public FieldAccessExpressionBuilder(final FieldAccessExpressionTree fieldAccessExpressionTree) {
        super(fieldAccessExpressionTree);
        this.selected = fieldAccessExpressionTree.getSelected();
        this.field = fieldAccessExpressionTree.getField();
    }

    @Override
    public FieldAccessExpressionBuilder self() {
        return this;
    }

    public ExpressionTree getSelected() {
        return selected;
    }

    public IdentifierTree getField() {
        return field;
    }

    public FieldAccessExpressionBuilder selected(final ExpressionTree selected) {
        this.selected = selected;
        return this;
    }

    public FieldAccessExpressionBuilder field(final IdentifierTree field) {
        this.field = field;
        return this;
    }

    @Override
    public FieldAccessExpressionTree build() {
        return new CFieldAccessExpressionTree(this);
    }
}
