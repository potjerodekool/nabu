package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.immutable.ImmutableWildcardType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableArrayType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableTypeVariable;
import lombok.extern.java.Log;

@Log
public class ArrayTypeVisitor extends AbstractSignatureVisitor {

    private final MutableArrayType arrayType;

    protected ArrayTypeVisitor(final int api,
                               final AsmClassElementLoader loader,
                               final AbstractSignatureVisitor parent) {
        super(api, loader, parent);
        this.arrayType = new MutableArrayType(null);
        parent.setType(arrayType);
    }

    @Override
    public void visitClassType(final String name) {
        final var clazz = getLoader().resolveClass(name);
        final var type = getTypes().getDeclaredType(clazz);
        this.arrayType.setComponentType(type);
    }

    @Override
    public void visitTypeArgument() {
        final var componentType = (MutableClassType) arrayType.getComponentType();
        componentType.addParameterType(new ImmutableWildcardType());
    }

    @Override
    public void visitBaseType(final char descriptor) {
        final var baseType = createBaseType(descriptor);
        arrayType.setComponentType(baseType);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var typeVariable = new MutableTypeVariable(name);
        arrayType.setComponentType(typeVariable);
    }

    @Override
    protected void setType(final TypeMirror type) {
        this.arrayType.setComponentType(type);
    }

    @Override
    protected void addTypeArgument(final TypeMirror type) {
        final var componentType = (MutableClassType) arrayType.getComponentType();
        componentType.addParameterType(type);
    }

    @Override
    public void visitEnd() {
        //TODO
    }
}
