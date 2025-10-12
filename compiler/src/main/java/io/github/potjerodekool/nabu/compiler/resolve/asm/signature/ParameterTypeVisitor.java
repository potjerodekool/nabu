package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.Constants;

import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.*;

public class ParameterTypeVisitor extends AbstractVisitor {

    private MutableType parameterType;

    protected ParameterTypeVisitor(final int api,
                                   final ClassElementLoader loader,
                                   final AbstractVisitor parent,
                                   final ModuleSymbol moduleSymbol) {
        super(api, loader, parent, moduleSymbol);
    }

    @Override
    public void visitClassType(final String name) {
        parameterType = new MutableClassType(loadClass(Symbol.createFlatName(name)));
        parent.addParameterType(parameterType);
    }

    @Override
    public void visitBaseType(final char descriptor) {
        parameterType = new MutablePrimitiveType(descriptor);
        parent.addParameterType(parameterType);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectType = new MutableClassType(loadClass(Constants.OBJECT));
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
        addTypeArgument(new MutableWildcardType(
                null,
                null
        ));
    }

    @Override
    public void visitInnerClassType(final String name) {
        final var innerName = ((MutableClassType)parent.getLastParameterType()).getClassName() + "$" + name;
        final var element = loadClass(innerName);
        parent.replaceLastParameterType(new MutableClassType(element));
    }
}
