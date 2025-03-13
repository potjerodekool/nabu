package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableTypeVariable;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableWildcardType;

public class BoundVisitor extends AbstractVisitor {

    private MutableType type;

    protected BoundVisitor(final int api,
                           final ClassElementLoader loader,
                           final AbstractVisitor parent) {
        super(api, loader, parent);
    }

    @Override
    public void visitClassType(final String name) {
        type = createMutableClass(loader.loadClass(name));
        final var parentType = (MutableTypeVariable) parent.getType();
        parentType.setUpperBound(type);
    }

    @Override
    public MutableType getType() {
        return type;
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
    public void visitInnerClassType(final String name) {
        final var classType = (MutableClassType) this.type;
        final var innerName = classType.getClassName() + "$" + name;
        final var element = loader.loadClass(innerName);
        this.type = new MutableClassType(element, classType);
    }

    @Override
    protected void addTypeArgument(final MutableType type) {
        final var classType = (MutableClassType) this.type;
        classType.addTypeArgument(type);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectType = new MutableClassType(loader.loadClass(Constants.OBJECT));
        this.type = new MutableTypeVariable(name, objectType, null);
        final var parentType = (MutableTypeVariable) parent.getType();
        parentType.setUpperBound(type);
    }
}
