package io.github.potjerodekool.nabu.compiler.type.immutable;

import io.github.potjerodekool.nabu.compiler.ast.element.AbstractSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.type.ClassType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

import java.util.List;
import java.util.stream.Collectors;

public class ImmutableClassType implements ClassType {

    private final Element element;
    private final ClassType outerType;

    private final List<TypeMirror> parameterTypes;

    public ImmutableClassType(final Element classSymbol,
                              final ClassType outerType,
                              final List<TypeMirror> parameterTypes) {
        element = classSymbol;
        this.outerType = outerType;
        this.parameterTypes = parameterTypes != null
                ? parameterTypes.stream().collect(Collectors.toUnmodifiableList())
                : null;
    }

    @Override
    public Element asElement() {
        return element;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.REFERENCE;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitClassType(this, param);
    }

    @Override
    public ClassType getOuterType() {
        return outerType;
    }

    @Override
    public List<TypeMirror> getParameterTypes() {
        return parameterTypes;
    }

    public ImmutableClassType withOuterType(final ClassType outerType) {
        return new ImmutableClassType(element, outerType, parameterTypes);
    }

}
