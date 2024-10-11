package io.github.potjerodekool.nabu.compiler.type.immutable;

import io.github.potjerodekool.nabu.compiler.ast.element.AbstractSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeVariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

public class ImmutableTypeVariable implements TypeVariable {

    private final AbstractSymbol element;
    private final TypeMirror upperBound;
    private final TypeMirror lowerBound;

    public ImmutableTypeVariable(final String name,
                                 final TypeMirror upperBound,
                                 final TypeMirror lowerBound) {
        this.element = new TypeVariableSymbol(null, name, null);
        this.upperBound = upperBound;
        this.lowerBound = lowerBound;
    }

    @Override
    public AbstractSymbol asElement() {
        return element;
    }

    @Override
    public TypeMirror getUpperBound() {
        return upperBound;
    }

    @Override
    public TypeMirror getLowerBound() {
        return lowerBound;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.TYPEVAR;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeVariable(this, param);
    }
}
