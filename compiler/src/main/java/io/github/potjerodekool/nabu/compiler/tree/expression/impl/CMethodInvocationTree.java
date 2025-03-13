package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.MethodInvocationTreeBuilder;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CMethodInvocationTree extends CExpressionTree implements MethodInvocationTree {

    private final ExpressionTree target;

    private final ExpressionTree name;

    private final List<IdentifierTree> typeArguments = new ArrayList<>();

    private final List<ExpressionTree> arguments = new ArrayList<>();

    private ExecutableType methodType;

    public CMethodInvocationTree(final ExpressionTree target,
                                 final ExpressionTree name,
                                 final List<IdentifierTree> typeArguments,
                                 final List<ExpressionTree> arguments,
                                 final int lineNumber,
                                 final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.target = target;
        this.name = name;
        this.typeArguments.addAll(typeArguments);
        this.arguments.addAll(arguments);
    }

    public CMethodInvocationTree(final MethodInvocationTreeBuilder methodInvocationTreeBuilder) {
        super(methodInvocationTreeBuilder);
        this.target = methodInvocationTreeBuilder.getTarget();
        this.name = methodInvocationTreeBuilder.getName();
        this.typeArguments.addAll(methodInvocationTreeBuilder.getTypeArguments());
        this.arguments.addAll(methodInvocationTreeBuilder.getArguments());
        this.methodType = methodInvocationTreeBuilder.getMethodType();
    }

    public ExpressionTree getTarget() {
        return target;
    }


    public ExpressionTree getName() {
        return name;
    }


    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    @Override
    public void setArguments(final ArrayList<ExpressionTree> arguments) {
        this.arguments.clear();
        this.arguments.addAll(arguments);
    }


    public List<IdentifierTree> getTypeArguments() {
        return typeArguments;
    }

    public ExecutableType getMethodType() {
        return methodType;
    }

    public void setMethodType(final ExecutableType methodType) {
        this.methodType = methodType;

        if (methodType != null) {
            setType(methodType.getReturnType());
        } else {
            setType(null);
        }
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

    @Override
    public MethodInvocationTreeBuilder builder() {
        return new MethodInvocationTreeBuilder(this);
    }
}
