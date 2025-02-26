package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class MethodInvocationTree extends ExpressionTree {

    private ExpressionTree target;

    private ExpressionTree name;

    private final List<IdentifierTree> typeArguments = new ArrayList<>();

    private final List<ExpressionTree> arguments = new ArrayList<>();

    private ExecutableType methodType;

    public ExpressionTree getTarget() {
        return target;
    }

    public MethodInvocationTree target(final ExpressionTree target) {
        this.target = target;
        return this;
    }

    public ExpressionTree getName() {
        return name;
    }

    public MethodInvocationTree name(final ExpressionTree name) {
        this.name = name;
        return this;
    }

    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    public MethodInvocationTree argument(final ExpressionTree argument) {
        Objects.requireNonNull(argument);
        this.arguments.add(argument);
        return this;
    }

    public MethodInvocationTree arguments(final ExpressionTree... arguments) {
        this.arguments.addAll(Arrays.asList(arguments));
        return this;
    }

    public MethodInvocationTree arguments(final List<ExpressionTree> arguments) {
        this.arguments.addAll(arguments);
        return this;
    }

    public List<IdentifierTree> getTypeArguments() {
        return typeArguments;
    }

    public MethodInvocationTree typeArguments(final List<IdentifierTree> typeArguments) {
        this.typeArguments.clear();
        this.typeArguments.addAll(typeArguments);
        return this;
    }

    public MethodInvocationTree typeArguments(final IdentifierTree... typeArguments) {
        return typeArguments(List.of(typeArguments));
    }

    public ExecutableType getMethodType() {
        return methodType;
    }

    public void setMethodType(final ExecutableType methodType) {
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
