package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;

import java.util.Objects;

public class CVariableDeclaratorStatement extends Statement {

    private final ExpressionTree type;
    private final IdentifierTree ident;
    private final Tree value;

    public CVariableDeclaratorStatement(final ExpressionTree type,
                                        final IdentifierTree ident,
                                        final Tree value) {
        Objects.requireNonNull(type);
        this.type = type;
        this.ident = ident;
        this.value = value;
    }

    protected CVariableDeclaratorStatement(final CVariableDeclaratorStatementBuilder builder) {
        super(builder);
        this.type = builder.type;
        this.ident = builder.ident;
        this.value = builder.value;
    }

    public ExpressionTree getType() {
        return type;
    }

    public IdentifierTree getIdent() {
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

}
