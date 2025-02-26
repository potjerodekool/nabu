package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public abstract class ExpressionTree extends Tree {

    private Element symbol;

    private TypeMirror type;

    public ExpressionTree() {
    }

    public ExpressionTree(final CExpressionBuilder<?> builder) {
        super(builder);
        this.symbol = builder.symbol;
        this.type = builder.type;
    }

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

    public static abstract class CExpressionBuilder<E extends ExpressionTree> extends TreeBuilder<E, CExpressionBuilder<E>> {

        private final Element symbol;
        private TypeMirror type;

        protected CExpressionBuilder() {
            this(null);
        }

        protected CExpressionBuilder(final ExpressionTree original) {
            super(original);

            if (original != null) {
                this.symbol = original.symbol;
                this.type = original.type;
            } else {
                this.symbol = null;
                this.type = null;
            }
        }

        public CExpressionBuilder<E> type(final TypeMirror type) {
            this.type = type;
            return this;
        }
    }
}
