package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.*;
import io.github.potjerodekool.nabu.compiler.type.*;

public class ToIType implements TypeVisitor<IType, Void> {

    private final boolean signature;

    public ToIType() {
        this(true);
    }

    public ToIType(final boolean signature) {
        this.signature = signature;
    }

    @Override
    public IType visitUnknownType(final TypeMirror typeMirror, final Void param) {
        throw new TodoException();
    }
    @Override
    public IType visitDeclaredType(final DeclaredType classType, final Void param) {
        final var clazz = (TypeElement) classType.asElement();
        final var name = clazz.getQualifiedName();
        final var typeParams = classType.getTypeArguments() != null
                ? classType.getTypeArguments().stream()
                .map(typeParam -> typeParam.accept(this, param))
                .toList()
                : null;

        final var kind = clazz.getKind() == ElementKind.INTERFACE
                ? ITypeKind.INTERFACE
                : ITypeKind.CLASS;

        return IReferenceType.create(
                kind,
                name,
                typeParams
        );
    }

    @Override
    public IType visitNoType(final NoType noType, final Void param) {
        return IPrimitiveType.VOID;
    }

    @Override
    public IType visitPrimitiveType(final PrimitiveType primitiveType, final Void param) {
        return switch (primitiveType.getKind()) {
            case BOOLEAN -> IPrimitiveType.BOOLEAN;
            case CHAR -> IPrimitiveType.CHAR;
            case BYTE -> IPrimitiveType.BYTE;
            case SHORT -> IPrimitiveType.SHORT;
            case INT -> IPrimitiveType.INT;
            case FLOAT -> IPrimitiveType.FLOAT;
            case LONG -> IPrimitiveType.LONG;
            case DOUBLE -> IPrimitiveType.DOUBLE;
            default -> throw new TodoException();
        };
    }

    @Override
    public IType visitNullType(final NullType nullType, final Void param) {
        return IReferenceType.NULL;
    }

    @Override
    public IType visitVariableType(final VariableType variableType, final Void param) {
        return variableType.getInterferedType().accept(this, param);
    }

    @Override
    public IType visitWildcardType(final WildcardType wildcardType, final Void param) {
        final var extendsBound = wildcardType.getExtendsBound() != null
                ? wildcardType.getExtendsBound().accept(this, param)
                : null;

        final var superBound = wildcardType.getSuperBound() != null
                ? wildcardType.getSuperBound().accept(this, param) : null;

        return new IWildcardType(
                extendsBound,
                superBound
        );
    }

    @Override
    public IType visitTypeVariable(final TypeVariable typeVariable, final Void param) {
        if (typeVariable.getUpperBound() != null) {
            final var type = typeVariable.getUpperBound().accept(this, param);

            if (signature) {
                return new ITypeVariable(typeVariable.asElement().getSimpleName(), type, null);
            } else {
                return type;
            }
        } else if (typeVariable.getLowerBound() != null) {
            final var type = typeVariable.getLowerBound().accept(this, param);
            if (signature) {
                return new ITypeVariable(typeVariable.asElement().getSimpleName(), null, type);
            } else {
                return type;
            }
        } else {
            throw new TodoException();
        }
    }
}
