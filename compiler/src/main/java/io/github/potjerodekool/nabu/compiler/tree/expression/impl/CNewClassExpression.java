package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.NewClassExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.NewClassExpressionBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CNewClassExpression extends CExpressionTree implements NewClassExpression {

    private final ExpressionTree name;
    private final List<ExpressionTree> typeArguments = new ArrayList<>();
    private final List<ExpressionTree> arguments = new ArrayList<>();
    private final ClassDeclaration classDeclaration;

    public CNewClassExpression(final ExpressionTree name,
                               final List<ExpressionTree> typeArguments,
                               final List<ExpressionTree> arguments,
                               final ClassDeclaration classDeclaration,
                               final int lineNumber,
                               final int columnNumber) {
        super(lineNumber, columnNumber);
        this.typeArguments.addAll(typeArguments);
        this.name = name;
        this.arguments.addAll(arguments);
        this.classDeclaration = classDeclaration;
    }

    public CNewClassExpression(final NewClassExpressionBuilder newClassExpressionBuilder) {
        super(newClassExpressionBuilder);
        this.name = newClassExpressionBuilder.getName();
        this.typeArguments.addAll(newClassExpressionBuilder.getTypeArguments());
        this.arguments.addAll(newClassExpressionBuilder.getArguments());
        this.classDeclaration = newClassExpressionBuilder.getClassDeclaration();
    }

    @Override
    public ExpressionTree getName() {
        return name;
    }

    @Override
    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    @Override
    public List<ExpressionTree> getTypeArguments() {
        return typeArguments;
    }

    @Override
    public ClassDeclaration getClassDeclaration() {
        return classDeclaration;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitNewClass(this, param);
    }

    @Override
    public NewClassExpressionBuilder builder() {
        return new NewClassExpressionBuilder(this);
    }

    @Override
    public String toString() {
        return String.format("new %s(%s)", name, arguments.stream().map(ExpressionTree::toString).collect(Collectors.joining(", ")));
    }
}
