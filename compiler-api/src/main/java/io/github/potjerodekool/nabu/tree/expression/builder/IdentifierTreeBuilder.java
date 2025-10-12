package io.github.potjerodekool.nabu.tree.expression.builder;


import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;

public class IdentifierTreeBuilder extends ExpressionBuilder<IdentifierTree, IdentifierTreeBuilder> {

    private String name;

    public IdentifierTreeBuilder() {
    }

    public IdentifierTreeBuilder(final IdentifierTree original) {
        super(original);
        this.name = original.getName();
    }

    public String getName() {
        return name;
    }

    public IdentifierTreeBuilder name(final String name) {
        this.name = name;
        return this;
    }

    @Override
    public IdentifierTreeBuilder self() {
        return this;
    }

    @Override
    public IdentifierTree build() {
        return new CIdentifierTree(this);
    }
}
