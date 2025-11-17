package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ErrorSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.lang.model.element.builder.TypeElementBuilder;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ClassSymbolBuilder extends AbstractSymbolBuilder<TypeElementBuilder<ClassSymbol>> implements TypeElementBuilder<ClassSymbol> {

    private NestingKind nestingKind = NestingKind.TOP_LEVEL;
    private final List<TypeMirror> interfaces = new ArrayList<>();
    private TypeMirror outerType;
    private TypeMirror superclass;
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();

    @Override
    protected ClassSymbolBuilder self() {
        return this;
    }

    @Override
    public ClassSymbolBuilder nestingKind(final NestingKind nestingKind) {
        this.nestingKind = nestingKind;
        return this;
    }

    public ClassSymbolBuilder interfaceType(final TypeMirror typeMirror) {
        this.interfaces.add(typeMirror);
        return this;
    }

    @Override
    public ClassSymbolBuilder outerType(final DeclaredType outerType) {
        this.outerType = outerType;
        return this;
    }

    @Override
    public ClassSymbolBuilder typeParameter(final TypeParameterElement typeParameter) {
        this.typeParameters.add(typeParameter);
        return this;
    }

    public ClassSymbolBuilder typeParameters(final List<? extends TypeParameterElement> typeParameters) {
        this.typeParameters.clear();
        this.typeParameters.addAll(typeParameters);
        return this;
    }

    @Override
    public ClassSymbolBuilder superclass(final TypeMirror superclass) {
        this.superclass = superclass;
        return this;
    }

    @Override
    public ClassSymbol build() {
        return build(false);
    }

    @Override
    public ClassSymbol buildError() {
        return build(true);
    }

    private ClassSymbol build(final boolean errorType) {
        final var typeArguments = typeParameters.stream()
                .map(TypeParameterElement::asType)
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
