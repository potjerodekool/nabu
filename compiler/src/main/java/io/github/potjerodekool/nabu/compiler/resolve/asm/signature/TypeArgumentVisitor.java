package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.ClassType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeUtils;
import io.github.potjerodekool.nabu.compiler.type.immutable.ImmutableWildcardType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableTypeVariable;
import lombok.extern.java.Log;

@Log
public class TypeArgumentVisitor extends AbstractSignatureVisitor {

    private final char wildcard;
    private TypeMirror currenType;

    protected TypeArgumentVisitor(final int api,
                                  final AsmClassElementLoader loader,
                                  final AbstractSignatureVisitor parent,
                                  final char wildcard) {
        super(api, loader, parent);
        this.wildcard = wildcard;
    }

    @Override
    public void visitTypeVariable(final String name) {
        currenType = new MutableTypeVariable(name);
        parent.addTypeArgument(currenType);
    }

    @Override
    public void visitClassType(final String name) {
        final var clazz = getLoader().resolveClass(name);
        currenType = getTypes().getDeclaredType(clazz);
        parent.addTypeArgument(currenType);
    }

    @Override
    public void visitTypeArgument() {
        final var classType = (MutableClassType) currenType;
        classType.addParameterType(new ImmutableWildcardType(null, null));
    }

    @Override
    public void visitInnerClassType(final String name) {
        final var outerClassName = TypeUtils.INSTANCE.getClassName(currenType);
        final var innerClassName = outerClassName + "$" + name;

        final var innerClassElement = getLoader().resolveClass(innerClassName);
        final var innerType = (ClassType) getTypes().getDeclaredType(innerClassElement);

        final var outerClassType = (MutableClassType) currenType;
        outerClassType.toInnerClassType(innerType);
    }

    @Override
    protected void addTypeArgument(final TypeMirror type) {
        final var classType = (MutableClassType) currenType;
        classType.addParameterType(type);
    }

    @Override
    protected void setType(final TypeMirror type) {
        currenType = type;
        parent.addTypeArgument(currenType);
    }

    @Override
    public void visitEnd() {
        //TODO
    }
}
