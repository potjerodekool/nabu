package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ErrorSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.CErrorType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ClassSymbolBuilder extends AbstractSymbolBuilder<Symbol, ClassSymbolBuilder> {

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

    public TypeElement build() {
        return build(false);
    }

    public TypeElement buildError() {
        return build(true);
    }

    private TypeElement build(final boolean errorType) {
        final var typeArguments = typeParameters.stream()
                .map(it -> (AbstractType) it.asType())
                .toList();

        final AbstractType type;
        final ClassSymbol clazz;

        if (errorType) {
            final var errorSymbol = new ErrorSymbol(getName());
            errorSymbol.setKind(getKind());
            errorSymbol.setNestingKind(nestingKind);
            errorSymbol.setEnclosingElement((Symbol) getEnclosingElement());
            errorSymbol.setEnclosedElements(getEnclosedElements());
            errorSymbol.setAnnotations(getAnnotations());
            type = new CErrorType(errorSymbol);
            clazz = errorSymbol;
        } else {
            clazz = new ClassSymbol(
                    getKind(),
                    nestingKind,
                    getFlags(),
                    getName(),
                    null,
                    (Symbol) getEnclosingElement(),
                    getEnclosedElements(),
                    getAnnotations()
            );


            type = new CClassType(
                    outerType,
                    clazz,
                    typeArguments);
        }

        clazz.setInterfaces(interfaces);

        typeParameters.forEach(tp -> Objects.requireNonNull(tp.asType()));

        clazz.setType(type);
        clazz.setSuperClass(superclass);
        return clazz;
    }

}
