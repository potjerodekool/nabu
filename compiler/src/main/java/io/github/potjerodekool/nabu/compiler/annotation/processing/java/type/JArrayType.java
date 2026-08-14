package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVisitor;

public class JArrayType extends JAbstractType<io.github.potjerodekool.nabu.type.ArrayType> implements ArrayType {

    private final TypeMirror componentType;

    public JArrayType(final io.github.potjerodekool.nabu.type.ArrayType original) {
        super(TypeKind.ARRAY, original);
        this.componentType = TypeWrapperFactory.wrap(original.getComponentType());
    }

    @Override
    public TypeMirror getComponentType() {
        return componentType;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> v, final P p) {
        return v.visitArray(this, p);
    }
}
