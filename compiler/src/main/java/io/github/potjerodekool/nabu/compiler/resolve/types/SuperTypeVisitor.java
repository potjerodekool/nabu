package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class SuperTypeVisitor extends AbstractTypeVisitor<TypeMirror, TypeMirror> {

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror, final TypeMirror subType) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType superType,
                                        final TypeMirror subType) {
        if (superType.getTypeArguments().isEmpty()) {
            return superType;
        } else {
            throw new TodoException();
        }
    }
}
