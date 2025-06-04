package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ErrorSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ClassSymbolBuilder extends AbstractSymbolBuilder<ClassSymbolBuilder> {

    private NestingKind nestingKind = NestingKind.TOP_LEVEL;
    private final List<TypeMirror> interfaces = new ArrayList<>();
    private TypeMirror outerType;
    private TypeMirror superclass;
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();

    protected ClassSymbolBuilder self() {
        return this;
    }

    public ClassSymbolBuilder nestingKind(final NestingKind nestingKind) {
        this.nestingKind = nestingKind;
        return this;
    }

    public ClassSymbolBuilder interfaceType(final TypeMirror typeMirror) {
        this.interfaces.add(typeMirror);
        return this;
    }

    public ClassSymbolBuilder outerType(final TypeMirror outerType) {
        this.outerType = outerType;
        return this;
    }

    public ClassSymbolBuilder typeParameter(final TypeParameterElement typeParameter) {
        this.typeParameters.add(typeParameter);
        return this;
    }

    public ClassSymbolBuilder typeParameters(final List<TypeParameterElement> typeParameters) {
        this.typeParameters.clear();
        this.typeParameters.addAll(typeParameters);
        return this;
    }

    public ClassSymbolBuilder superclass(final TypeMirror superclass) {
        this.superclass = superclass;
        return this;
    }

    public ClassSymbol build() {
        return build(false);
    }

    public ClassSymbol buildError() {
        return build(true);
    }

    private ClassSymbol build(final boolean errorType) {
        final var typeArguments = typeParameters.stream()
                .map(it -> (AbstractType) it.asType())
                .toList();

        final CClassType type;
        final ClassSymbol clazz;

        if (errorType) {
            final var errorSymbol = new ErrorSymbol(getSimpleName());
            errorSymbol.setKind(getKind());
            errorSymbol.setNestingKind(nestingKind);
            errorSymbol.setEnclosingElement((Symbol) getEnclosingElement());
            errorSymbol.setEnclosedElements(getEnclosedElements());
            errorSymbol.setAnnotations(getAnnotations());
            clazz = errorSymbol;
        } else {
            clazz = new ClassSymbol(
                    getKind(),
                    nestingKind,
                    getFlags(),
                    getSimpleName(),
                    new CClassType(
                            null,
                            null,
                            List.of()
                    ),
                    (Symbol) getEnclosingElement(),
                    getEnclosedElements(),
                    getAnnotations()
            );

            type = (CClassType) clazz.asType();
            type.setOuterType(outerType);
            type.setTypeArguments(typeArguments);
        }

        clazz.setInterfaces(interfaces);

        typeParameters.forEach(tp -> Objects.requireNonNull(tp.asType()));

        clazz.setSuperClass(superclass);
        return clazz;
    }

}
