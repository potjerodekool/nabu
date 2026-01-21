package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;

import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableTypeVariable;

public class ExceptionTypeVisitor extends AbstractVisitor {

    private MutableType exceptionType;

    protected ExceptionTypeVisitor(final int api,
                                   final CompilerContext compilerContext,
                                   final AbstractVisitor parent,
                                   final ModuleSymbol moduleSymbol) {
        super(api, compilerContext, parent, moduleSymbol);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectType = new MutableClassType(loadClass(Constants.OBJECT));
        exceptionType = new MutableTypeVariable(name, objectType, null);
        parent.addExceptionType(exceptionType);
    }

    @Override
    public void visitClassType(final String name) {
        exceptionType = new MutableClassType(loadClass(Symbol.createFlatName(name)));
        parent.addExceptionType(exceptionType);
    }
}
