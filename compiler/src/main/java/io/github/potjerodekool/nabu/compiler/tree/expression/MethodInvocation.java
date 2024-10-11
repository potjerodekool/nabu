package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableMethodType;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Getter
public class MethodInvocation extends CExpression {

    private CExpression target;

    private CExpression name;

    private final List<CExpression> arguments = new ArrayList<>();

    private MutableMethodType methodType;

    public MethodInvocation target(final CExpression target) {
        this.target = target;
        return this;
    }

    public MethodInvocation name(final CExpression name) {
        this.name = name;
        return this;
    }

    public MethodInvocation argument(final CExpression argument) {
        Objects.requireNonNull(argument);
        this.arguments.add(argument);
        return this;
    }

    public MethodInvocation arguments(final CExpression... arguments) {
        for (final CExpression argument : arguments) {
            Objects.requireNonNull(argument);
        }

        this.arguments.addAll(Arrays.asList(arguments));
        return this;
    }

    public MutableMethodType getMethodType() {
        return methodType;
    }

    public void setMethodType(final MutableMethodType methodType) {
        if (methodType == null) {
            throw new NullPointerException();
        }

        this.methodType = methodType;
        setType(methodType.getReturnType());
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitMethodInvocation(this, param);
    }

    @Override
    public String toString() {
        final var builder = new StringBuilder();

        if (target != null) {
            builder.append(target).append(".");
        }

        final var args = arguments.stream()
                .map(Object::toString)
                .collect(Collectors.joining(","));

        builder.append(name);
        builder.append("(");
        builder.append(args);
        builder.append(")");

        return builder.toString();
    }
}
