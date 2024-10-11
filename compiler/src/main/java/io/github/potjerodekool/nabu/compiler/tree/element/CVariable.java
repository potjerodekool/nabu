package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.CExpression;

public class CVariable extends CElement<CVariable> {

    private CExpression type;

    private VariableElement varSymbol;

    public CExpression getType() {
        return type;
    }

    public CVariable type(final CExpression type) {
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
}
