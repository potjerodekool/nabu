package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableTypeVariable;

public class ExceptionTypeVisitor extends AbstractVisitor {

    private MutableType exceptionType;

    protected ExceptionTypeVisitor(final int api,
                                   final ClassElementLoader loader,
                                   final AbstractVisitor parent) {
        super(api, loader, parent);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectType = new MutableClassType(loader.resolveClass(Constants.OBJECT));
        exceptionType = new MutableTypeVariable(name, objectType, null);
        parent.addExceptionType(exceptionType);
    }

    @Override
    public void visitClassType(final String name) {
        exceptionType = new MutableClassType(loader.resolveClass(name));
        parent.addExceptionType(exceptionType);
    }
}
