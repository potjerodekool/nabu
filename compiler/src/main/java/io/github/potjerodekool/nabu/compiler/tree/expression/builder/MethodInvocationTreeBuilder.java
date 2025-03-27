package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CMethodInvocationTree;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;

import java.util.ArrayList;
import java.util.List;

public class MethodInvocationTreeBuilder extends ExpressionBuilder<MethodInvocationTree, MethodInvocationTreeBuilder> {

    private ExpressionTree target;

    private ExpressionTree name;

    private final List<IdentifierTree> typeArguments = new ArrayList<>();

    private final List<ExpressionTree> arguments = new ArrayList<>();

    private ExecutableType methodType;

    public MethodInvocationTreeBuilder() {
    }

    public MethodInvocationTreeBuilder(final MethodInvocationTree original) {
        super(original);
        this.target = original.getTarget();
        this.name = original.getName();
        this.typeArguments.addAll(original.getTypeArguments());
        this.arguments.addAll(original.getArguments());
        this.methodType = original.getMethodType();
    }

    @Override
    public MethodInvocationTreeBuilder self() {
        return this;
    }

    public ExpressionTree getTarget() {
        return target;
    }

    public MethodInvocationTreeBuilder target(final ExpressionTree target) {
        this.target = target;
        return this;
    }

    public ExpressionTree getName() {
        return name;
    }

    public MethodInvocationTreeBuilder name(final ExpressionTree name) {
        this.name = name;
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
