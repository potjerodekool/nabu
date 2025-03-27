package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.ImportItem;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;

public class CImportItemTree extends CTree implements ImportItem {

    private final boolean isStatic;
    private final FieldAccessExpressionTree qualified;
    private Element symbol;

    public CImportItemTree(final FieldAccessExpressionTree qualified,
                           final boolean isStatic,
                           final int lineNumber,
                           final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
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
        final var field = (IdentifierTree) qualified.getField();
        return field.getName().equals("*");
    }

    @Override
    public String getClassOrPackageName() {
        return isStarImport()
                ? getClassName(qualified.getTarget())
                : getClassName(qualified);
    }

    private String getClassName(final ExpressionTree expressionTree) {
        if (expressionTree instanceof IdentifierTree identifierTree) {
            return identifierTree.getName();
        } else {
            final var fieldAccess = (FieldAccessExpressionTree) expressionTree;
            final var packageName = getClassName(fieldAccess.getTarget());
            final var className = getClassName(fieldAccess.getField());
            return packageName + "." + className;
        }
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitImportItem(this, param);
    }
}
