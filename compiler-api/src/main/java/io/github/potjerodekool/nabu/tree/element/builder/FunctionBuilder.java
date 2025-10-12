package io.github.potjerodekool.nabu.tree.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.tree.builder.TreeBuilder;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.impl.CFunction;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

import java.util.ArrayList;
import java.util.List;

public class FunctionBuilder extends TreeBuilder<Function, FunctionBuilder> {

    private String simpleName;
    private Kind kind;
    private Modifiers modifiers;
    private final List<TypeParameterTree> typeParameters = new ArrayList<>();
    private final List<VariableDeclaratorTree> parameters = new ArrayList<>();
    private ExpressionTree returnType;
    private BlockStatementTree body;
    private ExecutableElement methodSymbol;
    private ExpressionTree defaultValue;
    private final List<Tree> thrownTypes = new ArrayList<>();
    private VariableDeclaratorTree receiverParameter;

    public FunctionBuilder() {
        this.modifiers = new Modifiers();
    }

    public FunctionBuilder(final Function function) {
        super(function);
        this.simpleName = function.getSimpleName();
        this.kind = function.getKind();
        this.modifiers = function.getModifiers();
        this.methodSymbol = function.getMethodSymbol();
        this.typeParameters.addAll(function.getTypeParameters());
        this.parameters.addAll(function.getParameters());
        this.returnType = function.getReturnType();
        this.body = function.getBody();
        this.thrownTypes.addAll(function.getThrownTypes());
        this.receiverParameter = function.getReceiverParameter();
    }

    public String getSimpleName() {
        return simpleName;
    }

    public Kind getKind() {
        return kind;
    }

    public Modifiers getModifiers() {
        return modifiers;
    }

    public ExecutableElement getMethodSymbol() {
        return methodSymbol;
    }

    public List<TypeParameterTree> getTypeParameters() {
        return typeParameters;
    }

    public List<VariableDeclaratorTree> getParameters() {
        return parameters;
    }

    public ExpressionTree getReturnType() {
        return returnType;
    }

    public List<Tree> getThrownTypes() {
        return thrownTypes;
    }

    public BlockStatementTree getBody() {
        return body;
    }

    public ExpressionTree getDefaultValue() {
        return defaultValue;
    }

    @Override
    public FunctionBuilder self() {
        return this;
    }

    public FunctionBuilder body(final BlockStatementTree body) {
        this.body = body;
        return this;
    }

    public FunctionBuilder returnType(final ExpressionTree returnType) {
        this.returnType = returnType;
        return this;
    }

    public FunctionBuilder defaultValue(final ExpressionTree defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public FunctionBuilder typeParameters(final List<TypeParameterTree> typeParameters) {
        this.typeParameters.addAll(typeParameters);
        return this;
    }

    public FunctionBuilder parameters(final List<VariableDeclaratorTree> parameters) {
        this.parameters.addAll(parameters);
        return this;
    }

    public FunctionBuilder method(final ExecutableElement methodSymbol) {
        this.methodSymbol = methodSymbol;
        return this;
    }

    @Override
    public Function build() {
        return new CFunction(this);
    }

    public FunctionBuilder simpleName(final String name) {
        this.simpleName = name;
        return this;
    }

    public FunctionBuilder kind(final Kind kind) {
        this.kind = kind;
        return this;
    }

    public FunctionBuilder modifiers(final Modifiers modifiers) {
        this.modifiers = modifiers;
        return this;
    }

    public FunctionBuilder thrownTypes(final List<Tree> thrownTypes) {
        this.thrownTypes.clear();
        this.thrownTypes.addAll(thrownTypes);
        return this;
    }

    public VariableDeclaratorTree getReceiverParameter() {
        return receiverParameter;
    }

    public FunctionBuilder receiver(final VariableDeclaratorTree receiverParameter) {
        this.receiverParameter = receiverParameter;
        return this;
    }
}