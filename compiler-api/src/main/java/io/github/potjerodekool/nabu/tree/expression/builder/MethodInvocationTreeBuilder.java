package io.github.potjerodekool.nabu.tree.expression.builder;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CMethodInvocationTree;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for Method invocation trees.
 */
public class MethodInvocationTreeBuilder extends ExpressionBuilder<MethodInvocationTreeBuilder> {

    private ExpressionTree methodSelector;

    private final List<IdentifierTree> typeArguments = new ArrayList<>();

    private final List<ExpressionTree> arguments = new ArrayList<>();

    private ExecutableType methodType;

    public MethodInvocationTreeBuilder() {
    }

    public MethodInvocationTreeBuilder(final MethodInvocationTree original) {
        super(original);
        this.methodSelector = original.getMethodSelector();
        this.typeArguments.addAll(original.getTypeArguments());
        this.arguments.addAll(original.getArguments());
        this.methodType = original.getMethodType();
    }

    @Override
    public MethodInvocationTreeBuilder self() {
        return this;
    }


    public ExpressionTree getMethodSelector() {
        return methodSelector;
    }

    public MethodInvocationTreeBuilder methodSelector(final ExpressionTree methodSelector) {
        this.methodSelector = methodSelector;
        return this;
    }

    public List<? extends IdentifierTree> getTypeArguments() {
        return typeArguments;
    }

    public MethodInvocationTreeBuilder typeArguments(final List<? extends IdentifierTree> typeArguments) {
        this.typeArguments.clear();
        this.typeArguments.addAll(typeArguments);
        return this;
    }

    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    public MethodInvocationTreeBuilder arguments(final List<? extends ExpressionTree> arguments) {
        this.arguments.clear();
        this.arguments.addAll(arguments);
        return this;
    }

    public ExecutableType getMethodType() {
        return methodType;
    }

    @Override
    public MethodInvocationTree build() {
        return new CMethodInvocationTree(this);
    }
}
