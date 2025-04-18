package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;

public class IFieldAccess extends IExpression {

    private final IExpression selected;
    private final String name;
    private final boolean isStatic;

    public IFieldAccess(final IExpression selected,
                        final String name,
                        final IType fieldType,
                        final boolean isStatic) {
        this.selected = selected;
        this.name = name;
        setType(fieldType);
        this.isStatic = isStatic;
    }

    public IExpression getSelected() {
        return selected;
    }

    public String getName() {
        return name;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public IType getFieldType() {
        return getType();
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitFieldAccess(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return List.of();
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return new IFieldAccess(
                selected,
                name,
                getType(),
                isStatic
        );
    }
}
