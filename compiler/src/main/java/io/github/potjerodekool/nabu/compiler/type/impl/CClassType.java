package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.symbol.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CClassType extends AbstractType implements DeclaredType {

    private TypeMirror outerType;

    private List<AbstractType> typeArguments;

    private List<TypeMirror> allParameters;

    private TypeMirror supertypeField;

    private List<TypeMirror> interfacesField;

    public CClassType(final TypeMirror outerType,
                      final TypeSymbol classSymbol,
                      final List<? extends AbstractType> typeArguments) {
        super(classSymbol);

        this.outerType = outerType;
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
    public TypeSymbol asElement() {
        return element;
    }

    @Override
    public TypeKind getKind() {
        complete();
        return TypeKind.DECLARED;
    }

    @Override
    public TypeMirror getEnclosingType() {
        return outerType;
    }

    public void setOuterType(final TypeMirror outerType) {
        this.outerType = outerType;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor,
                           final P param) {
        return visitor.visitDeclaredType(this, param);
    }

    @Override
    public List<AbstractType> getTypeArguments() {
        if (typeArguments == null) {
            complete();

            if (typeArguments == null) {
                typeArguments = new ArrayList<>();
            }
        }

        return typeArguments;
    }

    public void setTypeArguments(final List<AbstractType> typeArguments) {
        this.typeArguments = typeArguments;
    }

    private void complete() {
        element.complete();
    }

    @Override
    public String toString() {
        return element.getQualifiedName();
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

    public TypeMirror getSupertypeField() {
        return supertypeField;
    }

    public List<TypeMirror> getInterfacesField() {
        return interfacesField;
    }

    @Override
    public boolean isInterface() {
        return this.asTypeElement().getKind() == ElementKind.INTERFACE;
    }

    @Override
    public String getClassName() {
        return element.getQualifiedName();
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj instanceof CClassType other) {
            return Objects.equals(outerType, other.outerType)
                    && Objects.equals(typeArguments, other.typeArguments)
                    && Objects.equals(allParameters, other.allParameters)
                    && Objects.equals(supertypeField, other.supertypeField)
                    && Objects.equals(interfacesField, other.interfacesField);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                outerType,
                typeArguments,
                allParameters,
                supertypeField,
                interfacesField
        );
    }
}
