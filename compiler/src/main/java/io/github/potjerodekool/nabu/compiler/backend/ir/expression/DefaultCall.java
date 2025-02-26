package io.github.potjerodekool.nabu.compiler.backend.ir.expression;

import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.InvocationType;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.List;
import java.util.StringJoiner;

import static io.github.potjerodekool.nabu.compiler.util.CollectionUtils.headAndTailList;

public final class DefaultCall extends IExpression implements Call {

    private final Name owner;
    private final Name function;
    private final IType returnType;
    private final List<IType> paramTypes;
    private final List<IExpression> args;
    private final InvocationType invocationType;

    public DefaultCall(final InvocationType invocationType,
                final Name owner,
                final Name function,
                final IType returnType,
                final List<IType> paramTypes,
                final List<IExpression> args) {
        this.invocationType = invocationType;
        this.owner = owner;
        this.function = function;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.args = args;

        for (final IExpression arg : args) {
            if (arg == null) {
                throw new NullPointerException();
            }
        }
    }

    public InvocationType getInvocationType() {
        return invocationType;
    }

    public Name getOwner() {
        return owner;
    }

    public Name getFunction() {
        return function;
    }

    public IType getReturnType() {
        return returnType;
    }

    public List<IType> getParamTypes() {
        return paramTypes;
    }

    public List<IExpression> getArgs() {
        return args;
    }

    @Override
    public <P> Temp accept(final CodeVisitor<P> visitor, final P param) {
        return visitor.visitCall(this, param);
    }

    @Override
    public List<IExpression> kids() {
        return headAndTailList(function, args);
    }

    @Override
    public IExpression build(final List<IExpression> kids) {
        return new DefaultCall(invocationType, owner, (Name) kids.removeFirst(), returnType, paramTypes, kids);
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();

        final boolean staticOrSpecial = invocationType == InvocationType.STATIC ||
                invocationType == InvocationType.SPECIAL;

        if (!staticOrSpecial) {
            builder.append(args.getFirst()).append(".");
        }
        builder.append(function);

        builder.append("(");

        final int off = staticOrSpecial ? 0 : 1;

        final StringJoiner joiner = new StringJoiner(",");

        for (int i = off; i < args.size(); i++) {
            joiner.add(args.get(i).toString());
        }
        builder.append(joiner);
        builder.append(")");

        return builder.toString();
    }

    @Override
    public IType getType() {
        return getReturnType();
    }
}
