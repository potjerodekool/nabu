package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.CErrorType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ClassBuilder extends AbstractElementBuilder<ClassBuilder> {

    private NestingKind nestingKind = NestingKind.TOP_LEVEL;

    private final List<TypeMirror> interfaces = new ArrayList<>();
    private TypeMirror outerType;
    private TypeMirror superclass;
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();

    private final List<Element> enclosedElements = new ArrayList<>();

    public ClassBuilder() {
        kind = ElementKind.CLASS;
    }

    public ClassBuilder kind(final ElementKind kind) {
        this.kind = kind;
        return this;
    }

    @Override
    protected ClassBuilder self() {
        return this;
    }

    public ClassBuilder nestingKind(final NestingKind nestingKind) {
        this.nestingKind = nestingKind;
        return this;
    }

    public ClassBuilder interfaceType(final TypeMirror typeMirror) {
        this.interfaces.add(typeMirror);
        return this;
    }

    public TypeElement build() {
        return build(false);
    }

    public TypeElement buildError() {
        return build(true);
    }

    private TypeElement build(final boolean errorType) {
        final var clazz = new ClassSymbol(
                kind,
                nestingKind,
                modifiers,
                name,
                enclosing);

        enclosedElements.forEach(element -> {
            element.setEnclosingElement(clazz);
            clazz.addEnclosedElement(element);
        });

        typeParameters.forEach(clazz::addTypeParameter);
        interfaces.forEach(clazz::addInterface);

        typeParameters.forEach(tp -> Objects.requireNonNull(tp.asType()));

        final var typeArguments = typeParameters.stream()
                .map(TypeParameterElement::asType)
                .toList();

        final TypeMirror type;

        if (errorType) {
            type = new CErrorType(clazz);
        } else {
            type = new CClassType(
                    outerType,
                    clazz,
                    typeArguments);
        }

        clazz.setType(type);
        clazz.setSuperClass(superclass);
        return clazz;
    }


    public ClassBuilder outerType(final TypeMirror outerType) {
        this.outerType = outerType;
        return this;
    }

    public ClassBuilder typeParameter(final TypeParameterElement typeParameter) {
        Objects.requireNonNull(typeParameter);

        this.typeParameters.add(typeParameter);
        return this;
    }

    public ClassBuilder typeParameters(final List<TypeParameterElement> typeParameters) {
        typeParameters.forEach(Objects::requireNonNull);
        this.typeParameters.addAll(typeParameters);
        return this;
    }

    public ClassBuilder enclosedElement(final Element element) {
        this.enclosedElements.add(element);
        return this;
    }

    public ClassBuilder superclass(final TypeMirror superclass) {
        this.superclass = superclass;
        return this;
    }
}
