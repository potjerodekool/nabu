package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;
import io.github.potjerodekool.nabu.compiler.type.UnionType;

import java.util.List;
import java.util.stream.Collectors;

public class CUnionType extends AbstractType implements UnionType {

    private final List<TypeMirror> alternatives;

    protected CUnionType(final CClassType classType,
                         final List<TypeMirror> alternatives) {
        super(null);
        this.alternatives = List.copyOf(alternatives);
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.UNION;
    }

    @Override
    public List<? extends TypeMirror> getAlternatives() {
        return alternatives;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitUnionType(this, param);
    }

    @Override
    public boolean isCompound() {
        return asTypeElement().asType().isCompound();
    }

    @Override
    public String getClassName() {
        return alternatives.stream()
                .map(TypeMirror::getClassName)
                .collect(Collectors.joining(" & "));
    }
}
