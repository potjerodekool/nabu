package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.ast.symbol.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.*;
import org.objectweb.asm.signature.SignatureVisitor;

public class ArrayVisitor extends AbstractVisitor {

    private final MutableArrayType arrayType;
    private MutableType type;

    protected ArrayVisitor(final int api,
                           final ClassElementLoader loader,
                           final AbstractVisitor parent,
                           final ModuleSymbol moduleSymbol) {
        super(api, loader, parent, moduleSymbol);
        arrayType = new MutableArrayType(null);
        parent.setType(arrayType);
    }

    @Override
    public void visitClassType(final String name) {
        type = new MutableClassType(loadClass(Symbol.createFlatName(name)));
        arrayType.setComponentType(type);
    }

    @Override
    public SignatureVisitor visitTypeArgument(final char wildcard) {
        return super.visitTypeArgument(wildcard);
    }

    @Override
    public void visitTypeArgument() {
        final var classType = (MutableClassType) this.type;
        classType.addTypeArgument(new MutableWildcardType(
                null,
                null
        ));
    }

    @Override
    protected void addTypeArgument(final MutableType type) {
        final var classType = (MutableClassType) this.type;
        classType.addTypeArgument(type);
    }

    @Override
    public void visitBaseType(final char descriptor) {
        this.type = new MutablePrimitiveType(descriptor);
        arrayType.setComponentType(type);
    }

    @Override
    public MutableType getType() {
        return type;
    }

    @Override
    public void setType(final MutableType type) {
        this.type = type;
        this.arrayType.setComponentType(type);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectType = new MutableClassType(loadClass(Constants.OBJECT));
        type = new MutableTypeVariable(name, objectType, null);
        arrayType.setComponentType(type);
    }
}
