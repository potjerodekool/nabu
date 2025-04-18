package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.*;
import io.github.potjerodekool.nabu.compiler.type.*;

public class ToIType implements TypeVisitor<IType, Void> {

    public static final ToIType INSTANCE = new ToIType();

    private ToIType() {
    }

    public static IType toIType(final TypeMirror typeMirror) {
        return typeMirror.accept(INSTANCE, null);
    }

    @Override
    public IType visitUnknownType(final TypeMirror typeMirror, final Void param) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IType visitDeclaredType(final DeclaredType declaredType,
                                   final Void param) {
        final var clazz = (TypeElement) declaredType.asElement();
        final var name = clazz.getQualifiedName();
        final var typeParams = declaredType.getTypeArguments() != null
                ? declaredType.getTypeArguments().stream()
                .map(typeParam -> typeParam.accept(this, param))
                .toList()
                : null;

        final var enclosingType = declaredType.getEnclosingType() != null
                ? (IReferenceType) declaredType.getEnclosingType().accept(this, param)
                : null;

        return clazz.getKind() == ElementKind.INTERFACE
                ? IReferenceType.createInterfaceType(enclosingType, name, typeParams)
                : IReferenceType.createClassType(enclosingType, name, typeParams);
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
            default -> throw new IllegalArgumentException("Not a primitive type " + primitiveType.getKind());
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
        final var bound = wildcardType.getBound() != null
                ? wildcardType.getBound().accept(this, param)
                : null;

        return new IWildcardType(
                wildcardType.getBoundKind(),
                bound
        );
    }

    @Override
    public IType visitTypeVariable(final TypeVariable typeVariable,
                                   final Void param) {
        if (typeVariable.getUpperBound() != null) {
            final var type = typeVariable.getUpperBound().accept(this, param);
            return new ITypeVariable(typeVariable.asElement().getSimpleName(), type, null);
        } else if (typeVariable.getLowerBound() != null) {
            final var type = typeVariable.getLowerBound().accept(this, param);
            return new ITypeVariable(typeVariable.asElement().getSimpleName(), null, type);
        } else {
            throw new IllegalArgumentException("Type variable without upper or lower bound");
        }
    }

    @Override
    public IType visitArrayType(final ArrayType arrayType, final Void param) {
        final var componentType = arrayType.getComponentType().accept(this, param);
        return new IArrayType(componentType);
    }
}
