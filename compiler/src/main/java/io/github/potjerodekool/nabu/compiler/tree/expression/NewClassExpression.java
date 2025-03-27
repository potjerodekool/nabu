package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;

import java.util.List;

public interface NewClassExpression extends ExpressionTree {

    ExpressionTree getName();

    List<ExpressionTree> getArguments();

    List<ExpressionTree> getTypeArguments();

    ClassDeclaration getClassDeclaration();
}
