package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class DirectSuperTypeVisitor extends UnaryVisitor<AbstractType> {
    @Override
    public AbstractType visitUnknownType(final TypeMirror typeMirror, final Void param) {
        return (AbstractType) typeMirror;
    }

    @Override
    public AbstractType visitDeclaredType(final DeclaredType declaredType, final Void param) {
        final var superClass = declaredType.asTypeElement().getSuperclass();

        return (AbstractType) superClass;
    }
}
