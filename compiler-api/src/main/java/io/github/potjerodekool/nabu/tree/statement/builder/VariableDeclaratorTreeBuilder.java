package io.github.potjerodekool.nabu.tree.statement.builder;

import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CVariableDeclaratorTree;

import java.util.Objects;

/**
 * Builder for variable declarator statements.
 */
public class VariableDeclaratorTreeBuilder extends StatementTreeBuilder<VariableDeclaratorTreeBuilder> {

    private Kind kind;
    private Modifiers modifiers;
    private ExpressionTree variableType;
    private IdentifierTree name;
    private ExpressionTree nameExpression;
    private Tree value;

    public VariableDeclaratorTreeBuilder() {
        super();
        this.modifiers = new Modifiers();
    }

    public VariableDeclaratorTreeBuilder(final VariableDeclaratorTree original) {
        super(original);
        this.kind = original.getKind();
        this.modifiers = new Modifiers(original.getAnnotations(), original.getFlags());
        variableType = original.getVariableType();
        name = original.getName();
        value = original.getValue();
    }

    public Kind getKind() {
        return kind;
    }

    public Modifiers getModifiers() {
        return modifiers;
    }

    public ExpressionTree getVariableType() {
        return variableType;
    }

    public IdentifierTree getName() {
        return name;
    }

    public Tree getValue() {
        return value;
    }

    @Override
    public VariableDeclaratorTreeBuilder self() {
        return this;
    }

    public VariableDeclaratorTreeBuilder variableType(final ExpressionTree variableType) {
        Objects.requireNonNull(variableType);
        this.variableType = variableType;
        return this;
    }

    public VariableDeclaratorTreeBuilder name(final IdentifierTree name) {
        this.name = name;
        return this;
    }

    public VariableDeclaratorTreeBuilder value(final Tree value) {
        this.value = value;
        return this;
    }

    public VariableDeclaratorTreeBuilder modifiers(final Modifiers modifiers) {
        this.modifiers = modifiers;
        return this;
    }

    public VariableDeclaratorTreeBuilder kind(final Kind kind) {
        this.kind = kind;
        return this;
    }

    @Override
    public VariableDeclaratorTree build() {
        return new CVariableDeclaratorTree(this);
    }

    public ExpressionTree getNameExpression() {
        return nameExpression;
    }

    public VariableDeclaratorTreeBuilder nameExpression(final ExpressionTree nameExpression) {
        this.nameExpression = nameExpression;
        return this;
    }
}
