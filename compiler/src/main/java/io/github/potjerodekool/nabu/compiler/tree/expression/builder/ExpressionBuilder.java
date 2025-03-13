package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.builder.TreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public abstract class ExpressionBuilder<E extends ExpressionTree> extends TreeBuilder<E, ExpressionBuilder<E>> {

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

    public ExpressionBuilder<E> type(final TypeMirror type) {
        this.type = type;
        return this;
    }
}
