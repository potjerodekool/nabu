package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.TypeVariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.*;

public class CTypeVariable extends AbstractType implements TypeVariable {

    private final TypeParameterElement element;
    private final TypeMirror upperBound;
    private final TypeMirror lowerBound;

    public CTypeVariable(final String name) {
        this(name, null, null);
    }

    public CTypeVariable(final String name,
                         final TypeMirror upperBound,
                         final TypeMirror lowerBound) {
        this.element = new TypeVariableSymbol(name, null, this);

        if (upperBound == null && lowerBound == null) {
            throw new IllegalArgumentException();
        }

        validate(upperBound);
        validate(lowerBound);
        this.upperBound = upperBound;
        this.lowerBound = lowerBound;
    }

    private void validate(final TypeMirror typeMirror) {
        if (typeMirror instanceof PrimitiveType) {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public TypeParameterElement asElement() {
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
}
