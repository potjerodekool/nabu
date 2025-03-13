package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableType;

public class InterfaceVisitor extends AbstractVisitor {

    private MutableClassType type;

    protected InterfaceVisitor(final int api,
                               final ClassElementLoader loader,
                               final AbstractVisitor parent) {
        super(api, loader, parent);
    }

    @Override
    public void visitClassType(final String name) {
        type = new MutableClassType(loader.loadClass(name));
        parent.addInterfaceType(type);
    }

    @Override
    protected void addTypeArgument(final MutableType type) {
        this.type.addTypeArgument(type);
    }

    @Override
    public MutableClassType getType() {
        return type;
    }
}
