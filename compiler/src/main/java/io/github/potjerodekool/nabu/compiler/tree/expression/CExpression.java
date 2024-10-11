package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public abstract class CExpression extends Tree {

    private Element symbol;

    private TypeMirror type;

    public Element getSymbol() {
        return symbol;
    }

    public void setSymbol(final Element symbol) {
        this.symbol = symbol;
    }

    public TypeMirror getType() {
        return type;
    }

    public void setType(final TypeMirror type) {
        this.type = type;
    }

    public static abstract class CExpressionBuilder<E extends CExpression> extends TreeBuilder<E> {

        private final Element symbol;
        private final TypeMirror type;

        protected CExpressionBuilder(final CExpression original) {
            super(original);
            this.symbol = original.symbol;
            this.type = original.type;
        }

        @Override
        protected E fill(final E tree) {
            super.fill(tree);
            tree.setSymbol(symbol);
            tree.setType(type);
            return tree;
        }
    }
}
