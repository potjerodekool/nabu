package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.ArrayList;
import java.util.List;

public class CTypeApply extends CExpression implements Identifier {

    private final CExpression clazz;

    private List<CExpression> typeParameters;

    public CTypeApply(final CExpression clazz) {
        this(clazz, null);
    }

    public CTypeApply(final CExpression clazz,
                      final List<? extends CExpression> typeParameters) {
        this.clazz = clazz;

        if (typeParameters != null) {
            this.typeParameters = new ArrayList<>(typeParameters);
        } else {
            this.typeParameters = null;
        }
    }

    @Override
    public String getName() {
        final var ident = (CIdent) clazz;
        return ident.getName();
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeIdentifier(this, param);
    }

    public List<CExpression> getTypeParameters() {
        return typeParameters;
    }

    public void addTypeParameter(final CExpression typeParameter) {
        if (typeParameters == null) {
            typeParameters = new ArrayList<>();
        }
        typeParameters.add(typeParameter);
    }

    public void setTypeParameters(final List<CExpression> typeParameters) {
        this.typeParameters = typeParameters;
    }
}
