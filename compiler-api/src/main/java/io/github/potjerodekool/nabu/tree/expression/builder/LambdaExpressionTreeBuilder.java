package io.github.potjerodekool.nabu.tree.expression.builder;

import io.github.potjerodekool.nabu.tree.expression.LambdaExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CLambdaExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.ArrayList;
import java.util.List;

public class LambdaExpressionTreeBuilder extends ExpressionBuilder<LambdaExpressionTree, LambdaExpressionTreeBuilder> {

    private List<VariableDeclaratorTree> variables;

    private StatementTree body;

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

    public List<VariableDeclaratorTree> getVariables() {
        return variables;
    }

    public StatementTree getBody() {
        return body;
    }

    public ExecutableType getLambdaMethodType() {
        return lambdaMethodType;
    }

    @Override
    public LambdaExpressionTreeBuilder self() {
        return this;
    }

    public LambdaExpressionTreeBuilder variables(final List<VariableDeclaratorTree> variables) {
        this.variables = variables;
        return this;
    }

    public LambdaExpressionTreeBuilder body(final StatementTree body) {
        this.body = body;
        return this;
    }

    @Override
    public LambdaExpressionTree build() {
        return new CLambdaExpressionTree(this);
    }

}
