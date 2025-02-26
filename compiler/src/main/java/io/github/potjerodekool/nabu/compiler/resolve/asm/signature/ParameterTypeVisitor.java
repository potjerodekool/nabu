package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.*;

public class ParameterTypeVisitor extends AbstractVisitor {

    private MutableType parameterType;

    protected ParameterTypeVisitor(final int api,
                                   final ClassElementLoader loader,
                                   final AbstractVisitor parent) {
        super(api, loader, parent);
    }

    @Override
    public void visitClassType(final String name) {
        parameterType = new MutableClassType(loader.resolveClass(name));
        parent.addParameterType(parameterType);
    }

    @Override
    public void visitBaseType(final char descriptor) {
        parameterType = new MutablePrimitiveType(descriptor);
        parent.addParameterType(parameterType);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectType = new MutableClassType(loader.resolveClass(Constants.OBJECT));
        parameterType = new MutableTypeVariable(name, objectType, null);
        parent.addParameterType(parameterType);
    }

    @Override
    public MutableType getType() {
        return parameterType;
    }

    @Override
    protected void setType(final MutableType type) {
        this.parameterType = type;
        parent.addParameterType(parameterType);
    }

    @Override
    protected void addTypeArgument(final MutableType type) {
        final var classType = (MutableClassType) this.parameterType;
        classType.addTypeArgument(type);
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
    public void visitInnerClassType(final String name) {
        final var innerName = ((MutableClassType)parent.getLastParameterType()).getClassName() + "$" + name;
        final var element = loader.resolveClass(innerName);
        parent.replaceLastParameterType(new MutableClassType(element));
    }
}
