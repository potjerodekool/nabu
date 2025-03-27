package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.TypeVariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.*;

public class CTypeVariable extends AbstractType implements TypeVariable {

    private final TypeMirror upperBound;
    private final TypeMirror lowerBound;

    public CTypeVariable(final String name) {
        this(name, null, null);
    }

    public CTypeVariable(final String name,
                         final TypeMirror upperBound,
                         final TypeMirror lowerBound) {
        super(new TypeVariableSymbol(name, null));
        super.element.setType(this);
        this.upperBound = upperBound;
        this.lowerBound = lowerBound;
    }

    @Override
    public TypeVariableSymbol asElement() {
        return (TypeVariableSymbol) element;
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
    public boolean isCaptured() {
        return false;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.TYPEVAR;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitTypeVariable(this, param);
    }

    @Override
    public boolean equals(final Object obj) {
        return false;
    }

    @Override
    public String getClassName() {
        final var name = element.getSimpleName();

        if (upperBound != null) {
            return name + " extends " + upperBound.getClassName();
        } else if (lowerBound != null) {
            return name + " super " + lowerBound.getClassName();
        } else {
            return name;
        }
    }
}
