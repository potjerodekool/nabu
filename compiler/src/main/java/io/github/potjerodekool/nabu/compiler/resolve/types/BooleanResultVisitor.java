package io.github.potjerodekool.nabu.compiler.resolve.types;


import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVisitor;

public abstract class BooleanResultVisitor extends AbstractTypeVisitor<Boolean, TypeMirror> implements TypeVisitor<Boolean, TypeMirror> {

    @Override
    public Boolean visitUnknownType(final TypeMirror typeMirror, final TypeMirror param) {
        return false;
    }
}
