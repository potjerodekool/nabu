package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.type.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class CClassType extends AbstractType implements DeclaredType {

    private TypeMirror outerType;

    private List<TypeMirror> typeArguments;

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
            setTypeArguments(new ArrayList<>(typeArguments));
        } else {
            setTypeArguments(null);
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
    public List<TypeMirror> getTypeArguments() {
        if (typeArguments == null) {
           complete();

            if (typeArguments == null) {
                typeArguments = new ArrayList<>();
            }
        }

        return typeArguments;
    }

    public void setTypeArguments(final List<TypeMirror> typeArguments) {
        this.typeArguments = typeArguments != null ? new ArrayList<>(typeArguments) : null;
        validateTypeArguments();
    }

    public void addTypeArgument(final TypeMirror typeArgument) {
        if (this.typeArguments == null) {
            this.typeArguments = new ArrayList<>();
        }
        this.typeArguments.add(typeArgument);
        validateTypeArguments();
    }

    private void complete() {
        element.complete();
    }

    @Override
    public String toString() {
        final var name = element.getQualifiedName();

        if (!element.getTypeParameters().isEmpty()) {
            final var typeArgs = getTypeArguments().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(",", "(", ")"));

            return name + typeArgs;
        }

        return name;
    }

    @Override
    public List<? extends TypeMirror> getAllParameters() {
        complete();
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
            return Objects.equals(getEnclosingType(), other.getEnclosingType())
                    && Objects.equals(getTypeArguments(), other.getTypeArguments())
                    && Objects.equals(getAllParameters(), other.getAllParameters())
                    && Objects.equals(getSupertypeField(), other.getSupertypeField())
                    &&  Objects.equals(getInterfacesField(), other.getInterfacesField());
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
