package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.LambdaExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CLambdaExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclarator;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;

import java.util.ArrayList;
import java.util.List;

public class LambdaExpressionTreeBuilder extends ExpressionBuilder<LambdaExpressionTree> {

    private List<VariableDeclarator> variables;

    private Statement body;

    private ExecutableType lambdaMethodType;

    public LambdaExpressionTreeBuilder() {
        super();
        this.variables = new ArrayList<>();
    }

    public LambdaExpressionTreeBuilder(final LambdaExpressionTree lambdaExpressionTree) {
        super(lambdaExpressionTree);
        this.variables = lambdaExpressionTree.getVariables();
        this.body = lambdaExpressionTree.getBody();
        this.lambdaMethodType = lambdaExpressionTree.getLambdaMethodType();
    }

    public List<VariableDeclarator> getVariables() {
        return variables;
    }

    public Statement getBody() {
        return body;
    }

    public ExecutableType getLambdaMethodType() {
        return lambdaMethodType;
    }

    @Override
    public LambdaExpressionTreeBuilder self() {
        return this;
    }

    public LambdaExpressionTreeBuilder variables(final List<VariableDeclarator> variables) {
        this.variables = variables;
        return this;
    }

    public LambdaExpressionTreeBuilder body(final Statement body) {
        this.body = body;
        return this;
    }

    @Override
    public CLambdaExpressionTree build() {
        return new CLambdaExpressionTree(this);
    }

}
