package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.LambdaExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.LambdaExpressionTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclarator;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CLambdaExpressionTree extends CExpressionTree implements LambdaExpressionTree {

    private final List<VariableDeclarator> variables = new ArrayList<>();

    private Statement body;

    private ExecutableType lambdaMethodType;

    public CLambdaExpressionTree(final List<VariableDeclarator> parameters,
                                 final Statement body,
                                 final int lineNumber,
                                 final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.variables.addAll(parameters);
        this.body = body;
    }

    public CLambdaExpressionTree(final LambdaExpressionTreeBuilder builder) {
        super(builder);
        this.variables.addAll(builder.getVariables());
        this.body = builder.getBody();
        this.lambdaMethodType = builder.getLambdaMethodType();
    }

    public List<VariableDeclarator> getVariables() {
        return variables;
    }

    public CLambdaExpressionTree variable(final VariableDeclarator variable) {
        variables.add(variable);
        return this;
    }

    public CLambdaExpressionTree variable(final VariableDeclarator... variable) {
        variables.addAll(Arrays.asList(variable));
        return this;
    }

    public Statement getBody() {
        return body;
    }

    @Override
    public LambdaExpressionTree body(final Statement body) {
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