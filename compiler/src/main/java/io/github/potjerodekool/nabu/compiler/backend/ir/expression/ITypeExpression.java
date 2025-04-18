package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;

public class ITypeExpression extends IExpression {

    private final IType clazz;
    private final IExpression expression;
    private final Kind kind;

    public enum Kind {
        CAST,
        INSTANCEOF,
        NEW,
        NEWARRAY
    }

    public ITypeExpression(final Kind kind,
                           final IType clazz,
                           final IExpression expression) {
        this.kind = kind;
        this.clazz = clazz;
        this.expression = expression;
    }

    public Kind getKind() {
        return kind;
    }

    public IType getClazz() {
        return clazz;
    }

    public IExpression getExpression() {
        return expression;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitTypeExpression(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return List.of();
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return this;
    }
}
