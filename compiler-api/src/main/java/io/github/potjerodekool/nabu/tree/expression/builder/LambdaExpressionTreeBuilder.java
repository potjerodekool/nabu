package io.github.potjerodekool.nabu.tree.expression.builder;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.LambdaExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CLambdaExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for Lambda expressions.
 */
public class LambdaExpressionTreeBuilder extends ExpressionBuilder<LambdaExpressionTreeBuilder> {

    private List<Tree> variables;

    private Tree body;

    private ExecutableType lambdaMethodType;

    private ExpressionTree boundReceiver;

    private String boundReceiverName;

    public LambdaExpressionTreeBuilder() {
        super();
        this.variables = new ArrayList<>();
    }

    public LambdaExpressionTreeBuilder(final LambdaExpressionTree lambdaExpressionTree) {
        super(lambdaExpressionTree);
        this.variables = lambdaExpressionTree.getVariables();
        this.body = lambdaExpressionTree.getBody();
        this.lambdaMethodType = lambdaExpressionTree.getLambdaMethodType();
        this.boundReceiver = lambdaExpressionTree.getBoundReceiver();
        this.boundReceiverName = lambdaExpressionTree.getBoundReceiverName();
    }

    public ExpressionTree getBoundReceiver() {
        return boundReceiver;
    }

    public String getBoundReceiverName() {
        return boundReceiverName;
    }

    public LambdaExpressionTreeBuilder boundReceiver(final ExpressionTree receiver) {
        this.boundReceiver = receiver;
        return this;
    }

    public List<Tree> getVariables() {
        return variables;
    }

    public Tree getBody() {
        return body;
    }

    public ExecutableType getLambdaMethodType() {
        return lambdaMethodType;
    }

    @Override
    public LambdaExpressionTreeBuilder self() {
        return this;
    }

    public LambdaExpressionTreeBuilder variables(final List<Tree> variables) {
        this.variables = variables;
        return this;
    }

    public LambdaExpressionTreeBuilder body(final StatementTree body) {
        this.body = body;
        return this;
    }

    public LambdaExpressionTreeBuilder body(final ExpressionTree body) {
        this.body = body;
        return this;
    }

    @Override
    public LambdaExpressionTree build() {
        return new CLambdaExpressionTree(this);
    }

}
