package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.NewClassExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CNewClassExpression;

import java.util.ArrayList;
import java.util.List;

public class NewClassExpressionBuilder extends ExpressionBuilder<NewClassExpression, NewClassExpressionBuilder> {

    private ExpressionTree name;
    private final List<ExpressionTree> typeArguments = new ArrayList<>();
    private final List<ExpressionTree> arguments = new ArrayList<>();
    private final ClassDeclaration classDeclaration;

    public NewClassExpressionBuilder() {
        this.classDeclaration = null;
    }

    public NewClassExpressionBuilder(final NewClassExpression original) {
        this.name = original.getName();
        this.typeArguments.addAll(original.getTypeArguments());
        this.arguments.addAll(original.getArguments());
        this.classDeclaration = original.getClassDeclaration();
    }

    public ExpressionTree getName() {
        return name;
    }

    public NewClassExpressionBuilder name(final ExpressionTree name) {
        this.name = name;
        return this;
    }

    public List<ExpressionTree> getTypeArguments() {
        return typeArguments;
    }

    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    public NewClassExpressionBuilder arguments(final List<ExpressionTree> arguments) {
        this.arguments.clear();
        this.arguments.addAll(arguments);
        return this;
    }

    public ClassDeclaration getClassDeclaration() {
        return classDeclaration;
    }

    @Override
    public NewClassExpressionBuilder self() {
        return this;
    }

    @Override
    public NewClassExpression build() {
        return new CNewClassExpression(this);
    }
}
