package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ElementWrapperFactory;

import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVisitor;
import java.util.List;

public class JDeclaredType extends JAbstractType<io.github.potjerodekool.nabu.type.DeclaredType> implements DeclaredType {

    private final List<? extends TypeMirror> typeArguments;

    public JDeclaredType(final io.github.potjerodekool.nabu.type.DeclaredType typeMirror) {
        super(TypeKind.DECLARED, typeMirror, ElementWrapperFactory.wrap(typeMirror.asElement()));
        this.typeArguments = getOriginal().getTypeArguments().stream()
                .map(TypeWrapperFactory::wrap)
                .toList();
    }

    @Override
    public TypeMirror getEnclosingType() {
        final var enclosingType = getOriginal().getEnclosingType();
        if (enclosingType == null) {
            return new JNoType(TypeKind.NONE, null);
        }
        return TypeWrapperFactory.wrap(enclosingType);
    }

    @Override
    public List<? extends TypeMirror> getTypeArguments() {
        return typeArguments;
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof JDeclaredType otherDeclaredType) {
            return getOriginal().equals(otherDeclaredType.getOriginal());
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return getOriginal().hashCode();
    }

    @Override
    public String toString() {
        if (element instanceof QualifiedNameable qualifiedNameable) {
            return qualifiedNameable.getQualifiedName().toString();
        } else {
            return element.getSimpleName().toString();
        }
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> v, final P p) {
        return v.visitDeclared(this, p);
    }
}
