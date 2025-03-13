package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CVariableDeclarator;

import java.util.Objects;

public class VariableDeclaratorBuilder extends StatementBuilder<VariableDeclarator, VariableDeclaratorBuilder> {

    private Kind kind;
    private CModifiers modifiers;
    private ExpressionTree type;
    private IdentifierTree name;
    private ExpressionTree nameExpression;
    private Tree value;

    public VariableDeclaratorBuilder() {
        super();
    }

    public VariableDeclaratorBuilder(final VariableDeclarator original) {
        super(original);
        this.kind = original.getKind();
        this.modifiers = new CModifiers(original.getAnnotations(), original.getFlags());
        type = original.getType();
        name = original.getName();
        value = original.getValue();
    }

    public Kind getKind() {
        return kind;
    }

    public CModifiers getModifiers() {
        return modifiers;
    }

    public ExpressionTree getType() {
        return type;
    }

    public IdentifierTree getName() {
        return name;
    }

    public ExpressionTree getNameExpression() {
        return nameExpression;
    }

    public Tree getValue() {
        return value;
    }

    @Override
    public VariableDeclaratorBuilder self() {
        return this;
    }

    public VariableDeclaratorBuilder type(final ExpressionTree type) {
        Objects.requireNonNull(type);
        this.type = type;
        return this;
    }

    public VariableDeclaratorBuilder name(final IdentifierTree name) {
        this.name = name;
        return this;
    }

    public VariableDeclaratorBuilder nameExpression(final ExpressionTree nameExpression) {
        this.nameExpression = nameExpression;
        return this;
    }

    public VariableDeclaratorBuilder value(final Tree value) {
        this.value = value;
        return this;
    }

    public VariableDeclaratorBuilder modifiers(final CModifiers modifiers) {
        this.modifiers = modifiers;
        return this;
    }

    public VariableDeclaratorBuilder kind(final Kind kind) {
        this.kind = kind;
        return this;
    }

    @Override
    public VariableDeclarator build() {
        return new CVariableDeclarator(this);
    }
}
