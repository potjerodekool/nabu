package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;

import java.util.ArrayList;
import java.util.List;

public class NewClassExpression extends ExpressionTree {

    private final ExpressionTree name;
    private final List<ExpressionTree> arguments = new ArrayList<>();
    private final BlockStatement body;

    public NewClassExpression(final ExpressionTree name,
                              final List<ExpressionTree> arguments,
                              final List<Statement> classBody) {
        this.name = name;
        this.arguments.addAll(arguments);
        this.body = new BlockStatement(classBody);
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
