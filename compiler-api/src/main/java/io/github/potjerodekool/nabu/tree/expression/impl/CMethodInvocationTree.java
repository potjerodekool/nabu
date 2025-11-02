package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.tree.expression.builder.MethodInvocationTreeBuilder;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of MethodInvocation.
 */
public class CMethodInvocationTree extends CExpressionTree implements MethodInvocationTree {

    private final ExpressionTree methodSelector;

    private final List<IdentifierTree> typeArguments = new ArrayList<>();

    private final List<ExpressionTree> arguments = new ArrayList<>();

    private ExecutableType methodType;

    public CMethodInvocationTree(final ExpressionTree methodSelector,
                                 final List<IdentifierTree> typeArguments,
                                 final List<ExpressionTree> arguments) {
        this(methodSelector, typeArguments, arguments, -1, -1);
    }

    public CMethodInvocationTree(final ExpressionTree methodSelector,
                                 final List<IdentifierTree> typeArguments,
                                 final List<ExpressionTree> arguments,
                                 final int lineNumber,
                                 final int columnNumber) {
        super(lineNumber, columnNumber);
        this.methodSelector = methodSelector;
        this.typeArguments.addAll(typeArguments);
        this.arguments.addAll(arguments);
    }

    public CMethodInvocationTree(final MethodInvocationTreeBuilder methodInvocationTreeBuilder) {
        super(methodInvocationTreeBuilder);
        this.methodSelector = methodInvocationTreeBuilder.getMethodSelector();
        this.typeArguments.addAll(methodInvocationTreeBuilder.getTypeArguments());
        this.arguments.addAll(methodInvocationTreeBuilder.getArguments());
        this.methodType = methodInvocationTreeBuilder.getMethodType();
    }

    @Override
    public ExpressionTree getMethodSelector() {
        return methodSelector;
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


        final var args = arguments.stream()
                .map(Object::toString)
                .collect(Collectors.joining(","));

        builder.append(methodSelector);
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
