package io.github.potjerodekool.nabu.compiler.tree.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.TypeParameterTree;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.impl.CTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;

import java.util.ArrayList;
import java.util.List;

public class CFunction extends CTree implements Function {

    private final String simpleName;
    private final Kind kind;
    private final CModifiers modifiers;
    private final List<TypeParameterTree> typeParameters = new ArrayList<>();
    private final VariableDeclaratorTree receiverParameter;
    private final List<VariableDeclaratorTree> parameters = new ArrayList<>();
    private final ExpressionTree returnType;
    private final List<Tree> thrownTypes = new ArrayList<>();
    private final BlockStatementTree body;
    private final ExpressionTree defaultValue;

    private ExecutableElement methodSymbol;

    public CFunction(final String simpleName,
                     final Kind kind,
                     final CModifiers modifiers,
                     final List<TypeParameterTree> typeParameters,
                     final VariableDeclaratorTree receiverParameter,
                     final List<VariableDeclaratorTree> parameters,
                     final ExpressionTree returnType,
                     final List<Tree> thrownTypes,
                     final BlockStatementTree body,
                     final ExpressionTree defaultValue,
                     final int lineNumber,
                     final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.simpleName = simpleName;
        this.kind = kind;
        this.modifiers = modifiers;
        this.typeParameters.addAll(typeParameters);
        this.receiverParameter = receiverParameter;
        this.parameters.addAll(parameters);
        this.returnType = returnType;
        this.thrownTypes.addAll(thrownTypes);
        this.body = body;
        this.defaultValue = defaultValue;
    }

    public CFunction(final FunctionBuilder functionBuilder) {
        super(functionBuilder);
        this.simpleName = functionBuilder.getSimpleName();
        this.kind = functionBuilder.getKind();
        this.modifiers = functionBuilder.getModifiers();
        this.methodSymbol = functionBuilder.getMethodSymbol();
        this.typeParameters.addAll(functionBuilder.getTypeParameters());
        this.receiverParameter = functionBuilder.getReceiverParameter();
        this.parameters.addAll(functionBuilder.getParameters());
        this.returnType = functionBuilder.getReturnType();
        this.thrownTypes.addAll(functionBuilder.getThrownTypes());
        this.body = functionBuilder.getBody();
        this.defaultValue = functionBuilder.getDefaultValue();
    }

    @Override
    public ExecutableElement getMethodSymbol() {
        return methodSymbol;
    }

    @Override
    public void setMethodSymbol(final ExecutableElement methodSymbol) {
        this.methodSymbol = methodSymbol;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public Kind getKind() {
        return kind;
    }

    public CModifiers getModifiers() {
        return modifiers;
    }

    public ExpressionTree getDefaultValue() {
        return defaultValue;
    }

    @Override
    public VariableDeclaratorTree getReceiverParameter() {
        return receiverParameter;
    }

    public List<VariableDeclaratorTree> getParameters() {
        return parameters;
    }

    public ExpressionTree getReturnType() {
        return returnType;
    }

    @Override
    public List<Tree> getThrownTypes() {
        return thrownTypes;
    }

    public BlockStatementTree getBody() {
        return body;
    }

    public List<TypeParameterTree> getTypeParameters() {
        return typeParameters;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitFunction(this, param);
    }

    @Override
    public FunctionBuilder builder() {
        return new FunctionBuilder(this);
    }
}


