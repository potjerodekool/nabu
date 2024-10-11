package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.tree.expression.CExpression;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.ArrayList;
import java.util.List;

public class CFunction extends CElement<CFunction> {

    private final List<CVariable> parameters = new ArrayList<>();
    private CExpression returnType;

    private BlockStatement body;

    public MethodSymbol methodSymbol;

    public CFunction() {
        this.kind(Kind.METHOD);
    }

    public List<CVariable> getParameters() {
        return parameters;
    }

    public CFunction parameter(final CVariable functionParameter) {
        this.parameters.add(functionParameter);
        return this;
    }

    public CExpression getReturnType() {
        return returnType;
    }

    public CFunction returnType(final CExpression returnType) {
        this.returnType = returnType;
        return this;
    }

    public BlockStatement getBody() {
        return body;
    }

    public CFunction body(final BlockStatement body) {
        this.body = body;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitFunction(this, param);
    }
}
