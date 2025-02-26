package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TypeApplyTree extends ExpressionTree implements Identifier {

    private final ExpressionTree clazz;

    private List<ExpressionTree> typeParameters;

    public TypeApplyTree(final ExpressionTree clazz) {
        this(clazz, null);
    }

    public TypeApplyTree(final ExpressionTree clazz,
                         final List<? extends ExpressionTree> typeParameters) {
        this.clazz = clazz;

        if (typeParameters != null) {
            for (final ExpressionTree typeParameter : typeParameters) {
                Objects.requireNonNull(typeParameter);
            }

            this.typeParameters = new ArrayList<>(typeParameters);
        } else {
            this.typeParameters = null;
        }
    }

    @Override
    public String getName() {
        final var ident = (IdentifierTree) clazz;
        return ident.getName();
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeIdentifier(this, param);
    }

    public List<ExpressionTree> getTypeParameters() {
        return typeParameters;
    }

    public void addTypeParameter(final ExpressionTree typeParameter) {
        Objects.requireNonNull(typeParameter);

        if (typeParameters == null) {
            typeParameters = new ArrayList<>();
        }
        typeParameters.add(typeParameter);
    }

    public void setTypeParameters(final List<ExpressionTree> typeParameters) {
        if (typeParameters != null) {
            for (final ExpressionTree typeParameter : typeParameters) {
                Objects.requireNonNull(typeParameter);
            }
        }

        this.typeParameters = typeParameters;
    }
}
