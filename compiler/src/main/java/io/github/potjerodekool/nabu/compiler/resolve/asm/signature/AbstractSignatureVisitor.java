package io.github.potjerodekool.nabu.compiler.resolve.asm.signature;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.resolve.Types;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import lombok.extern.java.Log;
import org.objectweb.asm.signature.SignatureVisitor;

@Log
public abstract class AbstractSignatureVisitor extends SignatureVisitor {

    private final AsmClassElementLoader loader;
    private final Types types;
    protected final AbstractSignatureVisitor parent;

    protected AbstractSignatureVisitor(final int api,
                                       final AsmClassElementLoader loader,
                                       final AbstractSignatureVisitor parent) {
        super(api);
        this.loader = loader;
        this.types = loader.getTypes();
        this.parent = parent;
    }

    @Override
    public void visitFormalTypeParameter(final String name) {
        throw new TodoException();
    }

    @Override
    public SignatureVisitor visitClassBound() {
        throw new TodoException();
    }

    @Override
    public SignatureVisitor visitInterfaceBound() {
        throw new TodoException();
    }

    @Override
    public SignatureVisitor visitSuperclass() {
        return new SuperClassSignatureVisitor(
                api,
                loader,
                this
        );
    }

    @Override
    public SignatureVisitor visitInterface() {
        throw new TodoException();
    }

    @Override
    public SignatureVisitor visitParameterType() {
        throw new TodoException();
    }

    @Override
    public SignatureVisitor visitReturnType() {
        throw new TodoException();
    }

    @Override
    public SignatureVisitor visitExceptionType() {
        throw new TodoException();
    }

    @Override
    public void visitBaseType(final char descriptor) {
        throw new TodoException();
    }

    @Override
    public void visitTypeVariable(final String name) {
        throw new TodoException();
    }

    @Override
    public SignatureVisitor visitArrayType() {
        return new ArrayTypeVisitor(
                api,
                loader,
                this
        );
    }

    @Override
    public void visitClassType(final String name) {
        throw new TodoException();
    }

    @Override
    public void visitInnerClassType(final String name) {
        throw new TodoException();
    }

    @Override
    public void visitTypeArgument() {
        throw new TodoException();
    }

    @Override
    public SignatureVisitor visitTypeArgument(final char wildcard) {
        return new TypeArgumentVisitor(
                api,
                loader,
                this,
                wildcard
        );
    }

    @Override
    public void visitEnd() {
        throw new TodoException();
    }

    protected TypeMirror createBaseType(final char descriptor) {
        return switch (descriptor) {
            case 'Z' -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case 'C' -> types.getPrimitiveType(TypeKind.CHAR);
            case 'B' -> types.getPrimitiveType(TypeKind.BYTE);
            case 'S' -> types.getPrimitiveType(TypeKind.SHORT);
            case 'I' -> types.getPrimitiveType(TypeKind.INT);
            case 'F' -> types.getPrimitiveType(TypeKind.FLOAT);
            case 'L' -> types.getPrimitiveType(TypeKind.LONG);
            case 'D' -> types.getPrimitiveType(TypeKind.DOUBLE);
            default -> throw new TodoException("" + descriptor);
        };
    }

    protected AsmClassElementLoader getLoader() {
        return loader;
    }

    protected Types getTypes() {
        return types;
    }

    protected void setSuperClass(final TypeMirror superType) {
        throw new TodoException();
    }

    protected void setType(final TypeMirror type) {
        throw new TodoException(getClass().getName());
    }

    protected void addTypeArgument(final TypeMirror type) {
        log.warning("addTypeArgument: " + type);
    }
}
