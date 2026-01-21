package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVisitor;

public class JPrimitiveType extends JAbstractType<io.github.potjerodekool.nabu.type.PrimitiveType> implements PrimitiveType {

    public JPrimitiveType(final TypeKind typeKind,
                          final io.github.potjerodekool.nabu.type.PrimitiveType original) {
        super(typeKind, original);
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> v, final P p) {
        return v.visitPrimitive(this, p);
    }
}
