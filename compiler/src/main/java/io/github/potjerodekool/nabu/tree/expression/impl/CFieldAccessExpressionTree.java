package io.github.potjerodekool.nabu.tree.expression.impl;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;

/**
 * Implementation of FieldAccessExpressionTree.
 */
public class CFieldAccessExpressionTree extends CExpressionTree implements FieldAccessExpressionTree {

    private ExpressionTree selected;

    private IdentifierTree field;

    public CFieldAccessExpressionTree(final ExpressionTree selected,
                                      final IdentifierTree field) {
        this(selected, field, -1, -1);
    }

    public CFieldAccessExpressionTree(final ExpressionTree selected,
                                      final IdentifierTree field,
                                      final int lineNumber,
                                      final int columnNumber) {
        super(lineNumber, columnNumber);
        this.selected = selected;
        this.field = field;
    }

    public CFieldAccessExpressionTree(final FieldAccessExpressionBuilder fieldAccessExpressionBuilder) {
        super(fieldAccessExpressionBuilder);
        this.selected = fieldAccessExpressionBuilder.getSelected();
        this.field = fieldAccessExpressionBuilder.getField();
    }

    @Override
    public ExpressionTree getSelected() {
        return selected;
    }

    @Override
    public FieldAccessExpressionTree selected(final ExpressionTree selected) {
        this.selected = selected;
        return this;
    }

    @Override
    public IdentifierTree getField() {
        return field;
    }

    public CFieldAccessExpressionTree field(final IdentifierTree field) {
        this.field = field;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitFieldAccessExpression(this, param);
    }

    @Override
    public FieldAccessExpressionBuilder builder() {
        return new FieldAccessExpressionBuilder(this);
    }

    @Override
    public String toString() {
        if (selected == null) {
            return field.toString();
        } else {
            final var selectedStr = selected.toString();
            final var fieldStr = field.toString();
            return String.format("%s.%s", selectedStr, fieldStr);
        }
    }

}
