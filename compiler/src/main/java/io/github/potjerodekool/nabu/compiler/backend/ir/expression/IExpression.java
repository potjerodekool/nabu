package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;

public abstract class IExpression {

    private IType type;

    private int lineNumber = -1;

    public abstract <P> Temp accept(CodeVisitor<P> visitor, P param);

    public abstract List<IExpression> kids();

    public abstract IExpression build(List<IExpression> kids);

    public IType getType() {
        return type;
    }

    public void setType(final IType type) {
        this.type = type;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(final int lineNumber) {
        this.lineNumber = lineNumber;
    }
}
