package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import io.github.potjerodekool.nabu.type.TypeMirror;

import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeVisitor;

public class JNullType extends JAbstractType<TypeMirror> implements NullType {

    public JNullType(final TypeMirror original) {
        super(javax.lang.model.type.TypeKind.NULL, original);
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> v, final P p) {
        return v.visitNull(this, p);
    }
}
