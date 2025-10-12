package io.github.potjerodekool.nabu.tree.expression.builder;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.builder.TreeBuilder;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.type.TypeMirror;

public abstract class ExpressionBuilder<E extends ExpressionTree, EB extends TreeBuilder<E, EB>> extends TreeBuilder<E, EB> {

    private final Element symbol;
    private TypeMirror type;

    protected ExpressionBuilder() {
        this.symbol = null;
        this.type = null;
    }

    protected ExpressionBuilder(final ExpressionTree original) {
        super(original);

        this.symbol = original.getSymbol();
        this.type = original.getType();
    }

    public Element getSymbol() {
        return symbol;
    }

    public TypeMirror getType() {
        return type;
    }

    public EB type(final TypeMirror type) {
        this.type = type;
        return self();
    }
}
