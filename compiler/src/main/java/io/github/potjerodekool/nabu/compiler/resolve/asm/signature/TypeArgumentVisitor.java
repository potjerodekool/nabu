package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableTypeVariable;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableWildcardType;

public class TypeArgumentVisitor extends AbstractVisitor {

    private MutableType type;
    private final char wildcard;

    protected TypeArgumentVisitor(final int api,
                                  final ClassElementLoader loader,
                                  final AbstractVisitor parent,
                                  final char wildcard,
                                  final ModuleSymbol moduleSymbol) {
        super(api, loader, parent, moduleSymbol);
        this.wildcard = wildcard;
    }

    public void visitClassType(final String name) {
        type = new MutableClassType((TypeSymbol) loader.loadClass(moduleSymbol, name));
        parent.addTypeArgument(type);
    }

    @Override
    public void visitTypeVariable(final String name) {
        final var objectType = new MutableClassType((TypeSymbol)loader.loadClass(moduleSymbol, Constants.OBJECT));
        final var type = new MutableTypeVariable(name, objectType, null);
        final var classType = (MutableClassType) parent.getType();
        classType.addTypeArgument(process(type));
    }

    private MutableType process(final MutableType typeMirror) {
        if (wildcard == '-') {
            return new MutableWildcardType(
                    null,
                    typeMirror
            );
        } else if (wildcard == '+') {
            return new MutableWildcardType(
                    typeMirror,
                    null
            );
        } else {
            return typeMirror;
        }
    }

    @Override
    public void visitTypeArgument() {
        final var classType = (MutableClassType) type;
        classType.addTypeArgument(new MutableWildcardType(
                null,
                null
        ));
    }

    @Override
    public MutableType getType() {
        return type;
    }

    @Override
    public void setType(final MutableType type) {
        this.type = type;
    }

    @Override
    protected void addTypeArgument(final MutableType type) {
        ((MutableClassType) this.type).addTypeArgument(type);
    }

    @Override
    public void visitInnerClassType(final String name) {
        final var classType = (MutableClassType) this.type;
        final var innerName = classType.getClassName() + "$" + name;
        final var element = loader.loadClass(moduleSymbol, innerName);
        this.type = new MutableClassType((TypeSymbol) element, classType);
    }
}
