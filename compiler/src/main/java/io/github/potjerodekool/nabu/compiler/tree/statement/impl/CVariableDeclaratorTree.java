package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;

import java.util.List;
import java.util.Objects;

public class CVariableDeclaratorTree extends CStatementTree implements VariableDeclaratorTree {

    private final Kind kind;
    private final CModifiers modifiers;
    private final ExpressionTree type;
    private final IdentifierTree name;
    private final ExpressionTree nameExpression;
    private final Tree value;

    public CVariableDeclaratorTree(final Kind kind,
                                   final CModifiers modifiers,
                                   final ExpressionTree type,
                                   final IdentifierTree name,
                                   final ExpressionTree nameExpression,
                                   final Tree value,
                                   final int lineNumber,
                                   final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.kind = kind;
        Objects.requireNonNull(modifiers);
        this.modifiers = modifiers;
        this.type = type;
        this.name = name;
        this.nameExpression = nameExpression;
        this.value = value;
    }

    public CVariableDeclaratorTree(final VariableDeclaratorTreeBuilder builder) {
        super(builder);
        this.kind = builder.getKind();
        this.modifiers = builder.getModifiers();
        Objects.requireNonNull(modifiers);
        this.type = builder.getType();
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
    public ExpressionTree getType() {
        return type;
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

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitVariableDeclaratorStatement(this, param);
    }

    @Override
    public VariableDeclaratorTreeBuilder builder() {
        return new VariableDeclaratorTreeBuilder(this);
    }
}
