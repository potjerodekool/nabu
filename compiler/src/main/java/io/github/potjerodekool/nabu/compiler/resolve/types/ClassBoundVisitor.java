package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class ClassBoundVisitor extends UnaryVisitor<AbstractType> {
    @Override
    public AbstractType visitUnknownType(final TypeMirror typeMirror, final Void param) {
        return (AbstractType) typeMirror;
    }

    @Override
    public AbstractType visitDeclaredType(final DeclaredType declaredType, final Void param) {
        final var enclosing = visit(declaredType.getEnclosingType());

        if (enclosing != null) {
            return new CClassType(
                    enclosing,
                    (TypeSymbol) declaredType.asTypeElement(),
                    declaredType.getTypeArguments()
            );
        } else {
            return (AbstractType) declaredType;
        }
    }
}
