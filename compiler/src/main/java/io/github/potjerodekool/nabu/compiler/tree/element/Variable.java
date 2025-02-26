package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class Variable extends Element<Variable> {

    private ExpressionTree type;

    private VariableElement varSymbol;

    public Variable(final int lineNumber,
                    final int columnNumber) {
        super(lineNumber, columnNumber);
    }

    public ExpressionTree getType() {
        return type;
    }

    public Variable type(final ExpressionTree type) {
        this.type = type;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R,P> visitor, final P param) {
        return visitor.visitVariable(this, param);
    }

    public VariableElement getVarSymbol() {
        return varSymbol;
    }

    public void setVarSymbol(final VariableElement varSymbol) {
        this.varSymbol = varSymbol;
    }

    @Override
    protected Variable self() {
        return this;
    }
}
