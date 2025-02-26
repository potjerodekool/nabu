package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.util.ElementUtils;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CClassType extends AbstractType implements DeclaredType {

    private final Element element;

    private final TypeMirror outerType;

    private final List<TypeMirror> typeArguments;

    private List<TypeMirror> allParameters;

    public CClassType(final TypeMirror outerType,
                      final Element classSymbol,
                      final List<? extends TypeMirror> typeArguments) {
        this.outerType = outerType;
        this.element = classSymbol;
        if (typeArguments != null
                && !typeArguments.isEmpty()) {
            this.typeArguments = new ArrayList<>(typeArguments);
        } else {
            this.typeArguments = new ArrayList<>();
        }
        validateTypeArguments();
    }

    private void validateTypeArguments() {
        if (typeArguments != null) {
            typeArguments.forEach(Objects::requireNonNull);
        }
    }

    @Override
    public Element asElement() {
        return element;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.DECLARED;
    }

    @Override
    public TypeMirror getEnclosingType() {
        return outerType;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor,
                           final P param) {
        return visitor.visitDeclaredType(this, param);
    }

    @Override
    public List<? extends TypeMirror> getTypeArguments() {
        return typeArguments;
    }

    @Override
    public String toString() {
        return ElementUtils.getQualifiedName(element);
    }

    public CClassType withTypeArguments(final TypeMirror... typeParams) {
        return withTypeArguments(List.of(typeParams));
    }

    public CClassType withTypeArguments(final List<TypeMirror> typeParams) {
        return new CClassType(
                outerType,
                element,
                typeParams
        );
    }

    @Override
    public List<? extends TypeMirror> getAllParameters() {
        if (allParameters == null) {
            allParameters = new ArrayList<>();

            if (getEnclosingType() != null) {
                allParameters.addAll(getEnclosingType().getAllParameters());
            }

            allParameters.addAll(getTypeArguments());
        }

        return allParameters;
    }

    @Override
    public boolean isParameterized() {
        return !getAllParameters().isEmpty();
    }
}
