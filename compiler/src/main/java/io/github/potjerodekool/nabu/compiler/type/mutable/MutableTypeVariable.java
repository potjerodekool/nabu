package io.github.potjerodekool.nabu.compiler.type.mutable;

import io.github.potjerodekool.nabu.compiler.ast.element.AbstractSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeVariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

public class MutableTypeVariable implements TypeVariable {

    private AbstractSymbol element;
    private TypeMirror upperBound;
    private TypeMirror lowerBound;

    public MutableTypeVariable(final String name) {
        this.element = new TypeVariableSymbol(null, name, null);
    }

    @Override
    public AbstractSymbol asElement() {
        return element;
    }

    public void setElement(final AbstractSymbol element) {
        this.element = element;
    }

    @Override
    public TypeMirror getUpperBound() {
        return upperBound;
    }

    public void setUpperBound(final TypeMirror upperBound) {
        this.upperBound = upperBound;
    }

    @Override
    public TypeMirror getLowerBound() {
        return lowerBound;
    }

    public void setLowerBound(final TypeMirror lowerBound) {
        this.lowerBound = lowerBound;
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
