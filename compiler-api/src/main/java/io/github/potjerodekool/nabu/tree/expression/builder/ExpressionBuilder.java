package io.github.potjerodekool.nabu.tree.expression.builder;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.builder.TreeBuilder;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.type.TypeMirror;

/**
 * Base class for expression builders.
 * @param <EB> Type of ExpressionBuilder.
 */
public class ExpressionBuilder<EB extends TreeBuilder<EB>> extends TreeBuilder<EB> {

    private final Element symbol;
    private TypeMirror type;
    private final ExpressionTree original;

    protected ExpressionBuilder() {
        this.symbol = null;
        this.type = null;
        this.original = null;
    }

    public ExpressionBuilder(final ExpressionTree original) {
        super(original);
        this.symbol = original.getSymbol();
        this.type = original.getType();
        this.original = original;
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

    @Override
    public EB self() {
        return (EB) this;
    }

    @Override
    public Tree build() {
        return original;
    }
}
