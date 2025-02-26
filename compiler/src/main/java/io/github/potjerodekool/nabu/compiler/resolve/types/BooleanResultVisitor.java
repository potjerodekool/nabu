package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.*;


public abstract class BooleanResultVisitor extends AbstractTypeVisitor<Boolean, TypeMirror> implements TypeVisitor<Boolean, TypeMirror> {

    @Override
    public Boolean visitUnknownType(final TypeMirror typeMirror, final TypeMirror param) {
        return false;
    }
}
