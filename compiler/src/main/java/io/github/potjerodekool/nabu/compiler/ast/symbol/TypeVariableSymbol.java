package io.github.potjerodekool.nabu.compiler.ast.symbol;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;

import java.util.List;

public class TypeVariableSymbol extends TypeSymbol implements TypeParameterElement {

    public TypeVariableSymbol(final String name,
                              final Symbol owner) {
        super(ElementKind.TYPE_PARAMETER, 0, name, null, owner);
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj instanceof TypeParameterElement other) {
            return getSimpleName().equals(other.getSimpleName());
        }

        return false;
    }

    @Override
    public Element getGenericElement() {
        return getEnclosingElement();
    }

    @Override
    public List<? extends TypeMirror> getBounds() {
        final var typeVariable = (TypeVariable) asType();
        final var upperBound = typeVariable.getUpperBound();

        if (!upperBound.isCompound()) {
            return List.of(upperBound);
        } else {
            final var classTypeBound = (CClassType) upperBound;
            final var typeElement = classTypeBound.asTypeElement();

            if (typeElement.getErasureField().isInterface()) {
                return CollectionUtils.headAndTailList(
                        classTypeBound.getSupertypeField(),
                        classTypeBound.getInterfacesField()
                );
            } else {
                return classTypeBound.getInterfacesField();
            }
        }
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitTypeParameter(this, p);
    }

    @Override
    public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
        return v.visitTypeVariable(this, p);
    }
}
