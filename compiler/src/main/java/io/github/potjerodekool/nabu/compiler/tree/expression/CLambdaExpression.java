package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementExpression;
import io.github.potjerodekool.nabu.compiler.type.MethodType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CLambdaExpression extends CExpression {

    private final List<CVariable> variables = new ArrayList<>();

    private Statement body;

    private MethodType lambdaMethodType;

    public List<CVariable> getVariables() {
        return variables;
    }

    public CLambdaExpression variable(final CVariable variable) {
        variables.add(variable);
        return this;
    }

    public CLambdaExpression variable(final CVariable... variable) {
        variables.addAll(Arrays.asList(variable));
        return this;
    }

    public Statement getBody() {
        return body;
    }

    public CLambdaExpression body(final Statement body) {
        this.body = body;
        return this;
    }

    public CLambdaExpression body(final CExpression body) {
        this.body = new StatementExpression(body);
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitLambdaExpression(this, param);
    }

    public MethodType getLambdaMethodType() {
        return lambdaMethodType;
    }

    public void setLambdaMethodType(final MethodType lambdaMethodType) {
        this.lambdaMethodType = lambdaMethodType;
    }
}
