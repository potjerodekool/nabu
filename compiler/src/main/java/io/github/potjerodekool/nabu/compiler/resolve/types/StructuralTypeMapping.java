package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.TypeMapping;
import io.github.potjerodekool.nabu.type.DeclaredType;

public class StructuralTypeMapping<S> extends TypeMapping<S> {

    @Override
    public AbstractType visitDeclaredType(final DeclaredType declaredType, final S param) {
        final var enclosingType = declaredType.getEnclosingType();
        final var enclosingType2 = visit(enclosingType, param);

        final var typeParameters = declaredType.getTypeArguments();
        final var typeParameters2 = visit(typeParameters, param);

        if (enclosingType2 == enclosingType && typeParameters2 == typeParameters) {
            return (AbstractType) declaredType;
        } else {
            return new CClassType(
                    enclosingType2,
                    (TypeSymbol) declaredType.asTypeElement(),
                    typeParameters2
            );
        }
    }
}
