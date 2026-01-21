package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import io.github.potjerodekool.nabu.type.BoundKind;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.type.WildcardType;

public class JWildcartType extends JAbstractType<io.github.potjerodekool.nabu.type.WildcardType> implements WildcardType {

    private final TypeMirror bound;
    private final BoundKind boundKind;


    public JWildcartType(final io.github.potjerodekool.nabu.type.WildcardType original) {
        super(TypeKind.WILDCARD, original);
        this.boundKind = original.getBoundKind();
        this.bound = TypeWrapperFactory.wrap(original.getBound());
    }

    @Override
    public TypeMirror getExtendsBound() {
        return boundKind == BoundKind.EXTENDS ? bound : null;
    }

    @Override
    public TypeMirror getSuperBound() {
        return boundKind == BoundKind.SUPER ? bound : null;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> v, final P p) {
        return v.visitWildcard(this, p);
    }
}
