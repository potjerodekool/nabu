package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeVisitor;

public class JTypeVariable extends JAbstractType<io.github.potjerodekool.nabu.type.TypeMirror> implements TypeVariable {

    private final TypeMirror upperBound;
    private final TypeMirror lowerBound;

    public JTypeVariable(final io.github.potjerodekool.nabu.type.TypeMirror original) {
        super(TypeKind.TYPEVAR, original);
        this.upperBound = TypeWrapperFactory.wrap(original.getUpperBound());
        this.lowerBound = TypeWrapperFactory.wrap(original.getLowerBound());
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
    public <R, P> R accept(final TypeVisitor<R, P> v, final P p) {
        return v.visitTypeVariable(this, p);
    }
}
