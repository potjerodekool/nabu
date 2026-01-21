package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable.MutableType;
import io.github.potjerodekool.nabu.util.Types;
import org.objectweb.asm.signature.SignatureVisitor;

public abstract class AbstractVisitor extends SignatureVisitor {

    protected final CompilerContext compilerContext;
    protected final ClassElementLoader loader;
    protected final Types types;
    protected final AbstractVisitor parent;
    protected final ModuleSymbol moduleSymbol;

    protected AbstractVisitor(final int api,
                              final CompilerContext compilerContext,
                              final AbstractVisitor parent,
                              final ModuleSymbol moduleSymbol) {
        super(api);
        this.compilerContext = compilerContext;
        this.loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getTypes();
        this.parent = parent;
        this.moduleSymbol = moduleSymbol;
    }

    protected TypeSymbol loadClass(final String name) {
        var clazz = loader.loadClass(moduleSymbol, name);

        if (clazz == null) {
            clazz = types.getErrorType(name).asTypeElement();
        }

        return (TypeSymbol) clazz;
    }

    @Override
    public void visitFormalTypeParameter(final String name) {
        throw new TodoException(getClass().getName());
    }

    @Override
    public SignatureVisitor visitClassBound() {
        return new BoundVisitor(api, compilerContext, this, moduleSymbol);
    }

    @Override
    public SignatureVisitor visitInterfaceBound() {
        return new BoundVisitor(api, compilerContext, this, moduleSymbol);
    }

    @Override
    public SignatureVisitor visitSuperclass() {
        return new SuperClassVisitor(api, compilerContext, this, moduleSymbol);
    }

    @Override
    public SignatureVisitor visitInterface() {
        return new InterfaceVisitor(api, compilerContext, this, moduleSymbol);
    }

    @Override
    public SignatureVisitor visitParameterType() {
        return new ParameterTypeVisitor(api, compilerContext, this, moduleSymbol);
    }

    @Override
    public SignatureVisitor visitReturnType() {
        return new ReturnTypeVisitor(api, compilerContext, this, moduleSymbol);
    }

    @Override
    public SignatureVisitor visitExceptionType() {
        return new ExceptionTypeVisitor(api, compilerContext, this, moduleSymbol);
    }

    @Override
    public void visitBaseType(final char descriptor) {
        throw new TodoException(getClass().getName());
    }

    @Override
    public void visitTypeVariable(final String name) {
        throw new TodoException(getClass().getName());
    }

    @Override
    public SignatureVisitor visitArrayType() {
        return new ArrayVisitor(api, compilerContext, this, moduleSymbol);
    }

    @Override
    public void visitClassType(final String name) {
        throw new TodoException(getClass().getName());
    }

    @Override
    public void visitInnerClassType(final String name) {
        throw new TodoException(getClass().getName());
    }

    @Override
    public void visitTypeArgument() {
        throw new TodoException(getClass().getName());
    }

    @Override
    public SignatureVisitor visitTypeArgument(final char wildcard) {
        return new TypeArgumentVisitor(api, compilerContext, this, wildcard, moduleSymbol);
    }

    protected MutableType getType() {
        throw new TodoException(getClass().getName());
    }

    protected void setType(final MutableType type) {
        throw new TodoException(getClass().getName());
    }

    protected void addTypeArgument(final MutableType type) {
        throw new TodoException(getClass().getName());
    }

    protected MutableType getSuperType() {
        throw new TodoException(getClass().getName());
    }

    protected void setSuperType(final MutableType type) {
        throw new TodoException(getClass().getName());
    }

    protected void addInterfaceType(final MutableClassType type) {
        throw new TodoException(getClass().getName());
    }

    protected MutableType getReturnType() {
        throw new TodoException(getClass().getName());
    }

    protected void setReturnType(final MutableType returnType) {
        throw new TodoException(getClass().getName());
    }

    protected void addParameterType(final MutableType type) {
        throw new TodoException(getClass().getName());
    }

    protected void addExceptionType(final MutableType exceptionType) {
        throw new TodoException(getClass().getName());
    }

    public MutableType getLastParameterType() {
        throw new TodoException(getClass().getName());
    }

    public void replaceLastParameterType(final MutableType type) {
        throw new TodoException(getClass().getName());
    }

    protected MutableClassType createMutableClass(final TypeSymbol typeElement) {
        final var enclosingType = typeElement.asType().getEnclosingType();

        if (enclosingType == null) {
            return new MutableClassType(typeElement);
        } else {
            final var mutableEnclosingType = createMutableClass((ClassSymbol) enclosingType.asTypeElement());
            return new MutableClassType(typeElement, mutableEnclosingType);
        }
    }
}
