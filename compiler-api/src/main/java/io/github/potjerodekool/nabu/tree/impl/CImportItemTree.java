package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.ImportItem;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;

/**
 * Implementation of ImportItem.
 */
public final class CImportItemTree extends CTree implements ImportItem {

    private final boolean isStatic;
    private final FieldAccessExpressionTree qualified;
    private Element symbol;

    public CImportItemTree(final FieldAccessExpressionTree qualified,
                           final boolean isStatic,
                           final int lineNumber,
                           final int columnNumber) {
        super(lineNumber, columnNumber);
        this.qualified = qualified;
        this.isStatic = isStatic;
    }

    @Override
    public FieldAccessExpressionTree getQualified() {
        return qualified;
    }

    @Override
    public boolean isStatic() {
        return isStatic;
    }

    @Override
    public Element getSymbol() {
        return symbol;
    }

    @Override
    public void setSymbol(final Element symbol) {
        this.symbol = symbol;
    }

    @Override
    public boolean isStarImport() {
        final var fieldName = getFieldName(qualified);
        return fieldName.equals("*");
    }

    private String getFieldName(final FieldAccessExpressionTree fieldAccessExpressionTree) {
        final var field = fieldAccessExpressionTree.getField();

        if (field instanceof IdentifierTree identifierTree) {
            return identifierTree.getName();
        } else if (field instanceof FieldAccessExpressionTree sub) {
            return getFieldName(sub);
        } else {
            return "";
        }
    }


    @Override
    public String getClassOrPackageName() {
        return isStarImport()
                ? getClassName(qualified.getSelected())
                : getClassName(qualified);
    }

    private String getClassName(final ExpressionTree expressionTree) {
        if (expressionTree instanceof IdentifierTree identifierTree) {
            return identifierTree.getName();
        } else {
            final var fieldAccess = (FieldAccessExpressionTree) expressionTree;
            final var packageName = getClassName(fieldAccess.getSelected());
            final var className = getClassName(fieldAccess.getField());
            return packageName + "." + className;
        }
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitImportItem(this, param);
    }
}
