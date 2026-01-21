package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import io.github.potjerodekool.nabu.type.TypeMirror;

import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVisitor;

public class JNoType extends JAbstractType<TypeMirror> implements NoType {

    public JNoType(final TypeKind kind,
                   final TypeMirror original) {
        super(kind, original);
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> v, final P p) {
        return v.visitNoType(this, p);
    }
}
