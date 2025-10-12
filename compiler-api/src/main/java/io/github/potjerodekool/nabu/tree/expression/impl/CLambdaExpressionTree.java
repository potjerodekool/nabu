package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.LambdaExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.LambdaExpressionTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CLambdaExpressionTree extends CExpressionTree implements LambdaExpressionTree {

    private final List<VariableDeclaratorTree> variables = new ArrayList<>();

    private StatementTree body;

    private ExecutableType lambdaMethodType;

    public CLambdaExpressionTree(final List<VariableDeclaratorTree> parameters,
                                 final StatementTree body,
                                 final int lineNumber,
                                 final int columnNumber) {
        super(lineNumber, columnNumber);
        this.variables.addAll(parameters);
        this.body = body;
    }

    public CLambdaExpressionTree(final LambdaExpressionTreeBuilder builder) {
        super(builder);
        this.variables.addAll(builder.getVariables());
        this.body = builder.getBody();
        this.lambdaMethodType = builder.getLambdaMethodType();
    }

    public List<VariableDeclaratorTree> getVariables() {
        return variables;
    }

    public CLambdaExpressionTree variable(final VariableDeclaratorTree variable) {
        variables.add(variable);
        return this;
    }

    public CLambdaExpressionTree variable(final VariableDeclaratorTree... variable) {
        variables.addAll(Arrays.asList(variable));
        return this;
    }

    public StatementTree getBody() {
        return body;
    }

    @Override
    public LambdaExpressionTree body(final StatementTree body) {
        this.body = body;
        return this;
    }

    public CLambdaExpressionTree body(final ExpressionTree body) {
        this.body = TreeMaker.expressionStatement(
                body,
                body.getLineNumber(),
                body.getColumnNumber()
        );
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitLambdaExpression(this, param);
    }

    public ExecutableType getLambdaMethodType() {
        return lambdaMethodType;
    }

    @Override
    public void setLambdaMethodType(final ExecutableType lambdaMethodType) {
        this.lambdaMethodType = lambdaMethodType;
    }

    @Override
    public LambdaExpressionTreeBuilder builder() {
        return new LambdaExpressionTreeBuilder(this);
    }

}