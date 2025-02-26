package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;

import java.util.Objects;

public class CVariableDeclaratorStatementBuilder extends StatementBuilder<CVariableDeclaratorStatement> {

    ExpressionTree type;
    IdentifierTree ident;
    Tree value;

    public CVariableDeclaratorStatementBuilder(final CVariableDeclaratorStatement original) {
        super(original);
        type = original.getType();
        ident = original.getIdent();
        value = original.getValue();
    }

    @Override
    public CVariableDeclaratorStatementBuilder self() {
        return null;
    }

    public CVariableDeclaratorStatementBuilder type(final ExpressionTree type) {
        Objects.requireNonNull(type);
        this.type = type;
        return this;
    }

    public CVariableDeclaratorStatementBuilder ident(final IdentifierTree ident) {
        Objects.requireNonNull(ident);
        this.ident = ident;
        return this;
    }

    public CVariableDeclaratorStatementBuilder value(final Tree value) {
        this.value = value;
        return this;
    }

    @Override
    public CVariableDeclaratorStatement build() {
        return new CVariableDeclaratorStatement(this);
    }
}
