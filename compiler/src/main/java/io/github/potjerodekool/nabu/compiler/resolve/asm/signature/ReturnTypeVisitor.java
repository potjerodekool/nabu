package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.*;

public class ReturnTypeVisitor extends AbstractVisitor {

    private MutableType returnType;

    protected ReturnTypeVisitor(final int api, final ClassElementLoader loader, final AbstractVisitor parent) {
        super(api, loader, parent);
    }

    @Override
    public void visitClassType(final String name) {
        returnType = new MutableClassType(loader.loadClass(name));
        parent.setReturnType(returnType);
    }

    @Override
    public void visitBaseType(final char descriptor) {
        returnType = new MutablePrimitiveType(descriptor);
        parent.setReturnType(returnType);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectElement = loader.loadClass(Constants.OBJECT);
        final var objectType = new MutableClassType(objectElement);
        returnType = new MutableTypeVariable(name, objectType, null);
        parent.setReturnType(returnType);
    }

    @Override
    protected MutableType getType() {
        return returnType;
    }

    @Override
    protected void setType(final MutableType type) {
        this.returnType = type;
        parent.setReturnType(returnType);
    }

    @Override
    protected void addTypeArgument(final MutableType type) {
        final var classType = (MutableClassType) this.returnType;
        classType.addTypeArgument(type);
    }

    @Override
    public void visitTypeArgument() {
        addTypeArgument(new MutableWildcardType(
                null,
                null
        ));
    }

    @Override
    public void visitInnerClassType(final String name) {
        final var innerName = ((MutableClassType)parent.getReturnType()).getClassName() + "$" + name;
        final var element = loader.loadClass(innerName);
        parent.setReturnType(new MutableClassType(element));
    }
}
