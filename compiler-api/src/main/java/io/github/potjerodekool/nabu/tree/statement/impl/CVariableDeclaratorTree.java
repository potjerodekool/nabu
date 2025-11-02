package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;

import java.util.List;
import java.util.Objects;

/**
 * Implementation of VariableDeclaratorTree
 */
public class CVariableDeclaratorTree extends CStatementTree implements VariableDeclaratorTree {

    private final Kind kind;
    private final Modifiers modifiers;
    private final ExpressionTree variableType;
    private final IdentifierTree name;
    private final ExpressionTree nameExpression;
    private Tree value;

    public CVariableDeclaratorTree(final Kind kind,
                                   final Modifiers modifiers,
                                   final ExpressionTree type,
                                   final IdentifierTree name,
                                   final ExpressionTree nameExpression,
                                   final Tree value) {
        this(kind, modifiers, type, name, nameExpression, value, -1, -1);
    }

    public CVariableDeclaratorTree(final Kind kind,
                                   final Modifiers modifiers,
                                   final ExpressionTree variableType,
                                   final IdentifierTree name,
                                   final ExpressionTree nameExpression,
                                   final Tree value,
                                   final int lineNumber,
                                   final int columnNumber) {
        super(lineNumber, columnNumber);
        this.kind = kind;
        Objects.requireNonNull(modifiers);
        this.modifiers = modifiers;
        this.variableType = variableType;
        this.name = name;
        this.nameExpression = nameExpression;
        this.value = value;
    }

    public CVariableDeclaratorTree(final VariableDeclaratorTreeBuilder builder) {
        super(builder);
        this.kind = builder.getKind();
        this.modifiers = builder.getModifiers();
        this.variableType = builder.getVariableType();
        this.name = builder.getName();
        this.nameExpression = builder.getNameExpression();
        this.value = builder.getValue();
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public List<? extends AnnotationTree> getAnnotations() {
        return modifiers.getAnnotations();
    }

    @Override
    public long getFlags() {
        return modifiers.getFlags();
    }

    @Override
    public boolean hasFlag(final long flag) {
        return modifiers.hasFlag(flag);
    }

    @Override
    public ExpressionTree getVariableType() {
        return variableType;
    }

    @Override
    public IdentifierTree getName() {
        return name;
    }

    @Override
    public ExpressionTree getNameExpression() {
        return nameExpression;
    }

    @Override
    public Tree getValue() {
        return value;
    }

    public void setValue(final Tree value) {
        this.value = value;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitVariableDeclaratorStatement(this, param);
    }

    @Override
    public VariableDeclaratorTreeBuilder builder() {
        return new VariableDeclaratorTreeBuilder(this);
    }
}
