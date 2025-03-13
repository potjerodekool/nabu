package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.NewClassExpression;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;

import java.util.ArrayList;
import java.util.List;

public class CNewClassExpression extends CExpressionTree implements NewClassExpression {

    private final ExpressionTree name;
    private final List<ExpressionTree> arguments = new ArrayList<>();
    private final BlockStatement body;

    public CNewClassExpression(final ExpressionTree name,
                               final List<ExpressionTree> arguments,
                               final BlockStatement classBody,
                               final int lineNumber,
                               final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.name = name;
        this.arguments.addAll(arguments);
        this.body = classBody;
    }

    public ExpressionTree getName() {
        return name;
    }

    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    public BlockStatement getBody() {
        return body;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitNewClass(this, param);
    }
}
