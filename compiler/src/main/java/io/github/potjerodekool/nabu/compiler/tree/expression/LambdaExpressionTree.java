package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.Variable;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementExpression;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LambdaExpressionTree extends ExpressionTree {

    private final List<Variable> variables = new ArrayList<>();

    private Statement body;

    private ExecutableType lambdaMethodType;

    public LambdaExpressionTree() {
        super();
    }

    public LambdaExpressionTree(final LambdaExpressionTreeBuilder builder) {
        super(builder);
        this.variables.addAll(builder.variables);
        this.body = builder.body;
        this.lambdaMethodType = builder.lambdaMethodType;
    }

    public List<Variable> getVariables() {
        return variables;
    }

    public LambdaExpressionTree variable(final Variable variable) {
        variables.add(variable);
        return this;
    }

    public LambdaExpressionTree variable(final Variable... variable) {
        variables.addAll(Arrays.asList(variable));
        return this;
    }

    public Statement getBody() {
        return body;
    }

    public LambdaExpressionTree body(final Statement body) {
        this.body = body;
        return this;
    }

    public LambdaExpressionTree body(final ExpressionTree body) {
        this.body = new StatementExpression(body);
        this.body.setLineNumber(body.getLineNumber());
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitLambdaExpression(this, param);
    }

    @Override
    public void setType(final TypeMirror type) {
        super.setType(type);
    }

    public ExecutableType getLambdaMethodType() {
        return lambdaMethodType;
    }

    public void setLambdaMethodType(final ExecutableType lambdaMethodType) {
        this.lambdaMethodType = lambdaMethodType;
    }

    public LambdaExpressionTreeBuilder builder() {
        return new LambdaExpressionTreeBuilder(this);
    }

    public static class LambdaExpressionTreeBuilder extends CExpressionBuilder<LambdaExpressionTree> {

        private List<Variable> variables;

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

        @Override
        public CExpressionBuilder<LambdaExpressionTree> self() {
            return this;
        }

        public LambdaExpressionTreeBuilder variables(final List<Variable> variables) {
            this.variables = variables;
            return this;
        }

        public LambdaExpressionTreeBuilder body(final Statement body) {
            this.body = body;
            return this;
        }

        @Override
        public LambdaExpressionTree build() {
            return new LambdaExpressionTree(this);
        }

    }
}