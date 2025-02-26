package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.ArrayList;
import java.util.List;

public class Function extends Element<Function> {

    private final List<Variable> parameters = new ArrayList<>();
    private ExpressionTree returnType;
    private BlockStatement body;
    private ExpressionTree defaultValue;

    public ExecutableElement methodSymbol;

    public Function(final int lineNumber,
                    final int columnNumber) {
        super(lineNumber, columnNumber);
        this.kind(Kind.METHOD);
    }

    public Function(final CFunctionBuilder functionBuilder) {
        super(functionBuilder);
        this.enclosingElement(functionBuilder.enclosingElement);
        this.methodSymbol = functionBuilder.methodSymbol;
        this.parameters.addAll(functionBuilder.parameters);
        this.returnType = functionBuilder.returnType;
        this.body = functionBuilder.body;
        this.defaultValue = functionBuilder.defaultValue;
    }

    public ExpressionTree getDefaultValue() {
        return defaultValue;
    }

    public List<Variable> getParameters() {
        return parameters;
    }

    public Function parameter(final Variable functionParameter) {
        this.parameters.add(functionParameter);
        return this;
    }

    public ExpressionTree getReturnType() {
        return returnType;
    }

    public Function returnType(final ExpressionTree returnType) {
        this.returnType = returnType;
        return this;
    }

    public BlockStatement getBody() {
        return body;
    }

    public Function body(final BlockStatement body) {
        this.body = body;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitFunction(this, param);
    }

    public CFunctionBuilder builder() {
        return new CFunctionBuilder(this);
    }

    public static class CFunctionBuilder extends CElementBuilder<Function, CFunctionBuilder> {

        private Element<?> enclosingElement;
        private final List<Variable> parameters = new ArrayList<>();
        private ExpressionTree returnType;
        private BlockStatement body;
        private ExecutableElement methodSymbol;
        private ExpressionTree defaultValue;


        public CFunctionBuilder() {
        }

        public CFunctionBuilder(final Function function) {
            super(function);
            this.enclosingElement = function.getEnclosingElement();
            this.methodSymbol = function.methodSymbol;
            this.parameters.addAll(function.getParameters());
            this.returnType = function.getReturnType();
            this.body = function.getBody();
        }

        @Override
        public CFunctionBuilder self() {
            return this;
        }

        public CFunctionBuilder body(final BlockStatement body) {
            this.body = body;
            return this;
        }

        public CFunctionBuilder returnType(final ExpressionTree returnType) {
            this.returnType = returnType;
            return this;
        }

        public CFunctionBuilder defaultValue(final ExpressionTree defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        @Override
        public Function build() {
            return new Function(this);
        }
    }

    @Override
    protected Function self() {
        return this;
    }

}


