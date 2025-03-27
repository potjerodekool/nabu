package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.TypeUnionExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CTypeUnionTreeExpression;

import java.util.List;
import java.util.Objects;

public class TypeUnionExpressionBuilder extends ExpressionBuilder<TypeUnionExpressionTree, TypeUnionExpressionBuilder> {

    private List<ExpressionTree> alternatives;

    @Override
    public TypeUnionExpressionBuilder self() {
        return this;
    }

    public List<ExpressionTree> getAlternatives() {
        return Objects.requireNonNullElseGet(alternatives, List::of);
    }

    public TypeUnionExpressionBuilder alternatives(List<ExpressionTree> alternatives) {
        this.alternatives = alternatives;
        return this;
    }

    @Override
    public TypeUnionExpressionTree build() {
        return new CTypeUnionTreeExpression(this);
    }
}
