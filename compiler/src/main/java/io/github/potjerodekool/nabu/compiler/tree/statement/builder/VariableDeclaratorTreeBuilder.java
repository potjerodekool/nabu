package io.github.potjerodekool.nabu.compiler.tree.statement.builder;

import io.github.potjerodekool.nabu.compiler.tree.Modifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CVariableDeclaratorTree;

import java.util.Objects;

public class VariableDeclaratorTreeBuilder extends StatementTreeBuilder<VariableDeclaratorTree, VariableDeclaratorTreeBuilder> {

    private Kind kind;
    private Modifiers modifiers;
    private ExpressionTree type;
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
        type = original.getType();
        name = original.getName();
        value = original.getValue();
    }

    public Kind getKind() {
        return kind;
    }

    public Modifiers getModifiers() {
        return modifiers;
    }

    public ExpressionTree getType() {
        return type;
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

    public VariableDeclaratorTreeBuilder type(final ExpressionTree type) {
        Objects.requireNonNull(type);
        this.type = type;
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

    public VariableDeclaratorTreeBuilder nameExpresion(final ExpressionTree nameExpression) {
        this.nameExpression = nameExpression;
        return this;
    }
}
