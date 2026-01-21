package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.*;

public class SuperClassVisitor extends AbstractVisitor {

    protected SuperClassVisitor(final int api,
                                final CompilerContext compilerContext,
                                final AbstractVisitor parent,
                                final ModuleSymbol moduleSymbol) {
        super(api, compilerContext, parent, moduleSymbol);
    }

    @Override
    public void visitClassType(final String name) {
        final var type = new MutableClassType(loadClass(Symbol.createFlatName(name)));
        parent.setSuperType(type);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectType = new MutableClassType(loadClass(Constants.OBJECT));
        final var type = new MutableTypeVariable(name, objectType, null);
        parent.setSuperType(type);
    }

    @Override
    public void setType(final MutableType type) {
        parent.setSuperType(type);
    }

    @Override
    public void visitTypeArgument() {
        addTypeArgument(new MutableWildcardType(
                null,
                null
        ));
    }

    @Override
    protected void addTypeArgument(final MutableType type) {
        final var classType = (MutableClassType) parent.getSuperType();
        classType.addTypeArgument(type);
    }

    @Override
    public void visitInnerClassType(final String name) {
        final var innerName = ((MutableClassType)parent.getSuperType()).getClassName() + "$" + name;
        final var element = loadClass(innerName);
        parent.setSuperType(new MutableClassType(element));
    }

    @Override
    protected MutableType getType() {
        return parent.getSuperType();
    }
}
