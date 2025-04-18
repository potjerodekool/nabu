package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ExpList extends IExpression {

    private final List<IExpression> list;

    public ExpList(final IExpression a,
                   final IExpression b) {
        this(Arrays.asList(a,b));
    }

    public ExpList(final IExpression... expressions) {
        this(Arrays.asList(expressions));
    }

    public ExpList(final List<IExpression> list) {
        Objects.requireNonNull(list);
        list.forEach(Objects::requireNonNull);
        this.list = list;
    }

    public List<IExpression> getList() {
        return list;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitExpList(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return list;
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return new ExpList(kids);
    }

    @Override
    public String toString() {
        return list.stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
    }
}
