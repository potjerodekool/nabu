package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.CExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;

import java.util.Objects;

public class CVariableDeclaratorStatement extends Statement {

    private final CExpression type;
    private final CIdent ident;
    private final Tree value;

    public CVariableDeclaratorStatement(final CExpression type,
                                        final CIdent ident,
                                        final Tree value) {
        this.type = type;
        this.ident = ident;
        this.value = value;
    }

    public CExpression getType() {
        return type;
    }

    public CIdent getIdent() {
        return ident;
    }

    public Tree getValue() {
        return value;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitVariableDeclaratorStatement(this, param);
    }

    public CVariableDeclaratorStatementBuilder builder() {
        return new CVariableDeclaratorStatementBuilder(this);
    }

    public static class CVariableDeclaratorStatementBuilder extends StatementBuilder<CVariableDeclaratorStatement> {

        private CExpression type;
        private CIdent ident;
        private Tree value;

        protected CVariableDeclaratorStatementBuilder(final CVariableDeclaratorStatement original) {
            super(original);
            type = original.getType();
            ident = original.getIdent();
            value = original.getValue();
        }

        public CVariableDeclaratorStatementBuilder type(final CExpression type) {
            Objects.requireNonNull(type);
            this.type = type;
            return this;
        }

        public CVariableDeclaratorStatementBuilder ident(final CIdent ident) {
            Objects.requireNonNull(ident);
            this.ident = ident;
            return this;
        }

        public CVariableDeclaratorStatementBuilder value(final Tree value) {
            Objects.requireNonNull(value);
            this.value = value;
            return this;
        }

        @Override
        public CVariableDeclaratorStatement build() {
            return fill(new CVariableDeclaratorStatement(
                    type,
                    ident,
                    value
            ));
        }
    }
}
