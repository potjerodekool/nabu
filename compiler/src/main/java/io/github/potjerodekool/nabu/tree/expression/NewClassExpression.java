package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.expression.builder.NewClassExpressionBuilder;

import java.util.List;

/**
 * New class expression.
 * For example:
 *<p>
 * final Animal animal = new Dog() {
 *<p>
 * };
 */
public interface NewClassExpression extends ExpressionTree {

    ExpressionTree getName();

    List<ExpressionTree> getArguments();

    List<ExpressionTree> getTypeArguments();

    ClassDeclaration getClassDeclaration();

    NewClassExpressionBuilder builder();
}
