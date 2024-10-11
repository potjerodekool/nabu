package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.immutable.ImmutableWildcardType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableTypeVariable;

public class SuperClassSignatureVisitor extends AbstractSignatureVisitor {

    private TypeMirror superType;

    protected SuperClassSignatureVisitor(final int api,
                                         final AsmClassElementLoader loader,
                                         final AbstractSignatureVisitor parent) {
        super(api, loader, parent);
    }

    @Override
    public void visitClassType(final String name) {
        final var clazz = getLoader().resolveClass(name);
        this.superType = getTypes().getDeclaredType(clazz);
        parent.setSuperClass(superType);
    }

    @Override
    public void visitTypeArgument() {
        final var classType = (MutableClassType) superType;
        classType.addParameterType(new ImmutableWildcardType(null, null));
    }

    @Override
    public void visitTypeVariable(final String name) {
        this.superType = new MutableTypeVariable(name);
        parent.setSuperClass(superType);
    }

    @Override
    public void visitInnerClassType(final String name) {
        final var classType = (MutableClassType) superType;

        if (classType.getOuterType() != null) {
            throw new TodoException();
        }

        final var clazz = (ClassSymbol) classType.asElement();
        final var otherClassName = clazz.getQualifiedName().replace('.', '/');
        final var innerClassName = otherClassName + "$" + name;
        final var innerClass = getLoader().resolveClass(innerClassName);

        this.superType = getTypes().getDeclaredType(innerClass);
        parent.setSuperClass(superType);
    }

    @Override
    protected void addTypeArgument(final TypeMirror type) {
        final var superClassType = (MutableClassType) superType;
        superClassType.addParameterType(type);
    }

    @Override
    protected void setType(final TypeMirror type) {
        this.superType = type;
        parent.setSuperClass(superType);
    }

    @Override
    public void visitEnd() {
    }
}
