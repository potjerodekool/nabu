package io.github.potjerodekool.nabu.compiler.backend.ir.statement;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.IExpression;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.TempExpr;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;

public class IVariableDeclaratorStatement extends IStatement {

    private final VariableElement symbol;
    private final IType type;
    private final IExpression initExpression;
    private final TempExpr dst;

    public IVariableDeclaratorStatement(final VariableElement symbol,
                                        final IType type,
                                        final IExpression initExpression,
                                        final TempExpr dst) {
        this.symbol = symbol;
        this.type = type;
        this.initExpression = initExpression;
        this.dst = dst;
    }

    public VariableElement getSymbol() {
        return symbol;
    }

    public IType getType() {
        return type;
    }

    public IExpression getInitExpression() {
        return initExpression;
    }

    public TempExpr getDst() {
        return dst;
    }

    @Override
    public <P> void accept(final CodeVisitor<P> visitor, final P param) {
        visitor.visitVariableDeclaratorStatement(this, param);
    }

    @Override
    public List<IExpression> kids() {
        throw new TodoException();
    }

    @Override
    public IStatement build(final List<IExpression> kids) {
        throw new TodoException();
    }
}
