package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.*;

public class SuperClassVisitor extends AbstractVisitor {

    protected SuperClassVisitor(final int api,
                                final ClassElementLoader loader,
                                final AbstractVisitor parent) {
        super(api, loader, parent);
    }

    @Override
    public void visitClassType(final String name) {
        final var type = new MutableClassType(loader.resolveClass(name));
        parent.setSuperType(type);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectType = new MutableClassType(loader.resolveClass(Constants.OBJECT));
        final var type = new MutableTypeVariable(name, objectType, null);
        parent.setSuperType(type);
    }

    @Override
    public void setType(final MutableType type) {
        parent.setSuperType(type);
    }

    @Override
    public void visitTypeArgument() {
        final var objectType = new MutableClassType(loader.resolveClass(Constants.OBJECT));
        addTypeArgument(new MutableWildcardType(
                objectType,
                null
        ));
    }

    @Override
    protected void addTypeArgument(final MutableType type) {
        final var classType = (MutableClassType) parent.getSuperType();
        classType.addTypeArgument(type);
    }

    @Override
    public void visitBaseType(final char descriptor) {
        //parent.setSuperType(new MutablePrimitiveType(descriptor));
        throw new TodoException();
    }

    @Override
    public void visitInnerClassType(final String name) {
        final var innerName = ((MutableClassType)parent.getSuperType()).getClassName() + "$" + name;
        final var element = loader.resolveClass(innerName);
        parent.setSuperType(new MutableClassType(element));
    }

    @Override
    protected MutableType getType() {
        return parent.getSuperType();
    }
}
