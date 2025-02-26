package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.Types;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClassSymbol extends TypeSymbol implements TypeElement {

    private final NestingKind nestingKind;

    private String qualifiedName;

    private TypeMirror supeclass;

    private final List<TypeMirror> interfaces = new ArrayList<>();

    private final List<TypeParameterElement> typeParameters = new ArrayList<>();

    public ClassSymbol(final ElementKind kind,
                       final NestingKind nestingKind,
                       final Set<Modifier> modifiers,
                       final String name,
                       final Element owner) {
        super(kind, modifiers, name, owner);
        this.nestingKind = nestingKind;
    }

    @Override
    public NestingKind getNestingKind() {
        return nestingKind;
    }

    private void resolveQualifiedName() {
        if (qualifiedName != null) {
            return;
        }

        final var enclosing = getEnclosingElement();

        if (enclosing != null) {
            final var enclosingName = enclosing instanceof QualifiedNameable qn
                    ? qn.getQualifiedName()
                    : enclosing.getSimpleName();
            qualifiedName = enclosingName + "." + getSimpleName();
        } else {
            qualifiedName = getSimpleName();
        }
    }

    @Override
    public void setEnclosingElement(final Element enclosingElement) {
        this.qualifiedName = null;
        super.setEnclosingElement(enclosingElement);
    }

    @Override
    public String getQualifiedName() {
        resolveQualifiedName();
        return qualifiedName;
    }

    @Override
    public ExecutableElement findFunctionalMethod() {
        return getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(element -> (ExecutableElement) element)
                .findFirst()
                .orElse(null);
    }

    @Override
    public TypeMirror getSuperclass() {
        return supeclass;
    }

    public void setSuperClass(final TypeMirror supeclass) {
        this.supeclass = supeclass;
    }

    @Override
    public List<TypeMirror> getInterfaces() {
        return interfaces;
    }

    public void addInterface(final TypeMirror interfaceType) {
        this.interfaces.add(interfaceType);
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
        return typeParameters;
    }

    public void addTypeParameter(final TypeParameterElement typeParameterElement) {
        this.typeParameters.add(typeParameterElement);
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        throw new TodoException();
    }

    public TypeMirror erasure(final Types types) {
        if (erasureType == null) {
            final var type = asType();

            erasureType = new CClassType(
                    types.erasure(
                            type.getEnclosingType()
                    ),
                    this,
                    List.of()
            );
        }

        return erasureType;
    }
}
