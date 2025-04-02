package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.List;

public class ITypeExpression extends IExpression {

    private final String name;
    private final IExpression expression;
    private final Kind kind;

    public enum Kind {
        CAST,
        INSTANCEOF
    }

    public ITypeExpression(final Kind kind,
                           final String name,
                           final IExpression expression) {
        this.kind = kind;
        this.name = name;
        this.expression = expression;
    }

    public Kind getKind() {
        return kind;
    }

    public String getName() {
        return name;
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
