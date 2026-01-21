package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.*;

public class ReturnTypeVisitor extends AbstractVisitor {

    private MutableType returnType;

    protected ReturnTypeVisitor(final int api,
                                final CompilerContext compilerContext,
                                final AbstractVisitor parent,
                                final ModuleSymbol moduleSymbol) {
        super(api, compilerContext, parent, moduleSymbol);
    }

    @Override
    public void visitClassType(final String name) {
        returnType = new MutableClassType(loadClass(Symbol.createFlatName(name)));
        parent.setReturnType(returnType);
    }

    @Override
    public void visitBaseType(final char descriptor) {
        returnType = new MutablePrimitiveType(descriptor);
        parent.setReturnType(returnType);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectElement = loadClass(Constants.OBJECT);
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
        final var element = loadClass(innerName);
        parent.setReturnType(new MutableClassType(element));
    }
}
