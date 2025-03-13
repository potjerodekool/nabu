package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclarator;

import java.util.List;

public class CVariableDeclarator extends CStatement implements VariableDeclarator {

    private final Kind kind;
    private final CModifiers modifiers;
    private final ExpressionTree type;
    private final IdentifierTree name;
    private final ExpressionTree nameExpression;
    private final Tree value;

    public CVariableDeclarator(final Kind kind,
                               final CModifiers modifiers,
                               final ExpressionTree type,
                               final IdentifierTree name,
                               final ExpressionTree nameExpression,
                               final Tree value,
                               final int lineNumber,
                               final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.kind = kind;
        this.modifiers = modifiers;
        this.type = type;
        this.name = name;
        this.nameExpression = nameExpression;
        this.value = value;
    }

    public CVariableDeclarator(final VariableDeclaratorBuilder builder) {
        super(builder);
        this.kind = builder.getKind();
        this.modifiers = builder.getModifiers();
        this.type = builder.getType();
        this.name = builder.getName();
        this.nameExpression = builder.getNameExpression();
        this.value = builder.getValue();
    }

    public Kind getKind() {
        return kind;
    }

    public List<? extends AnnotationTree> getAnnotations() {
        return modifiers.getAnnotations();
    }

    public long getFlags() {
        return modifiers.getFlags();
    }

    public boolean hasFlag(final int flag) {
        return modifiers.hasFlag(flag);
    }

    public ExpressionTree getType() {
        return type;
    }

    public IdentifierTree getName() {
        return name;
    }

    @Override
    public ExpressionTree getNameExpression() {
        return nameExpression;
    }

    public Tree getValue() {
        return value;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitVariableDeclaratorStatement(this, param);
    }

    @Override
    public VariableDeclaratorBuilder builder() {
        return new VariableDeclaratorBuilder(this);
    }
}
