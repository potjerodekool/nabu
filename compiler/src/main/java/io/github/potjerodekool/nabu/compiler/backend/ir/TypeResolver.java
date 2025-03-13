package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.*;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.List;

class TypeResolver extends AbstractTreeVisitor<IType, Object> implements TypeVisitor<IType, Object> {

    @Override
    public IType visitUnknownType(final TypeMirror typeMirror, final Object param) {
        return null;
    }

    @Override
    public IType visitTypeIdentifier(final TypeApplyTree typeIdentifier, final Object param) {
        final var typeParameters = typeIdentifier.getTypeParameters();
        final List<IType> types;

        if (typeParameters != null) {
            types = typeParameters.stream()
                    .map(typeParameter -> typeParameter.accept(this, param))
                    .toList();
        } else {
            types = null;
        }

        final var type = (DeclaredType) typeIdentifier.getType();
        final var clazz = (TypeElement) type.asElement();
        final var className = clazz.getQualifiedName();

        return clazz.getKind() == ElementKind.INTERFACE
                ? IReferenceType.createInterfaceType(null, className, types)
                : IReferenceType.createClassType(null, className, types);
    }

    @Override
    public IType visitIdentifier(final IdentifierTree identifier, final Object param) {
        final var type = identifier.getType();
        return type.accept(this, param);
    }

    @Override
    public IType visitPrimitiveType(final PrimitiveTypeTree primitiveType, final Object param) {
        return switch (primitiveType.getKind()) {
            case BOOLEAN -> IPrimitiveType.BOOLEAN;
            case INT -> IPrimitiveType.INT;
            case BYTE -> IPrimitiveType.BYTE;
            case SHORT -> IPrimitiveType.SHORT;
            case LONG -> IPrimitiveType.LONG;
            case CHAR -> IPrimitiveType.CHAR;
            case FLOAT -> IPrimitiveType.FLOAT;
            case DOUBLE -> IPrimitiveType.DOUBLE;
            case VOID -> IPrimitiveType.VOID;
        };
    }

    @Override
    public IType visitTypeNameExpression(final TypeNameExpressionTree typeNameExpression, final Object param) {
        final var className = asString(typeNameExpression);
        final var declaredType = (DeclaredType) typeNameExpression.getType();
        return declaredType.asElement().getKind() == ElementKind.INTERFACE
                ? IReferenceType.createInterfaceType(null, className, List.of())
                : IReferenceType.createClassType(null, className, List.of());
    }

    private String asString(final ExpressionTree expression) {
        return switch (expression) {
            case TypeNameExpressionTree typeNameExpression -> {
                final var packageName = asString(typeNameExpression.getPackageName());
                final var className = asString(typeNameExpression.getIdenifier());
                yield packageName + "." + className;
            }
            case IdentifierTree identifier -> identifier.getName();
            case TypeApplyTree typeIdentifier -> typeIdentifier.getName();
            default -> "";
        };
    }

    @Override
    public IType visitDeclaredType(final DeclaredType declaredType, final Object param) {
        final var clazz = (TypeElement) declaredType.asElement();
        final List<IType> typeArguments;

        if (declaredType.getTypeArguments() != null) {
            typeArguments = declaredType.getTypeArguments().stream()
                    .map(it -> it.accept(this, param))
                    .toList();
        } else {
            typeArguments = null;
        }

        final var className = clazz.getQualifiedName();

        return clazz.getKind() == ElementKind.INTERFACE
                ? IReferenceType.createInterfaceType(null, className, typeArguments)
                : IReferenceType.createClassType(null, className, typeArguments);
    }

    @Override
    public IType visitPrimitiveType(final PrimitiveType primitiveType, final Object param) {
        return switch (primitiveType.getKind()) {
            case BOOLEAN -> IPrimitiveType.BOOLEAN;
            case INT -> IPrimitiveType.INT;
            case BYTE -> IPrimitiveType.BYTE;
            case LONG -> IPrimitiveType.LONG;
            case CHAR -> IPrimitiveType.CHAR;
            case SHORT -> IPrimitiveType.SHORT;
            case FLOAT -> IPrimitiveType.FLOAT;
            case DOUBLE -> IPrimitiveType.DOUBLE;
            default -> throw new IllegalArgumentException("Not a primitive kind " + primitiveType.getKind());
        };
    }

    @Override
    public IType visitWildcardType(final WildcardType wildcardType, final Object param) {
        final var bound = wildcardType.getBound() != null
                ? wildcardType.getBound().accept(this, param)
                : null;
        return new IWildcardType(wildcardType.getBoundKind(), bound);
    }

    @Override
    public IType visitWildCardExpression(final WildcardExpressionTree wildCardExpression, final Object param) {
        final var bound = wildCardExpression.getBound() != null
                ? wildCardExpression.getBound().accept(this, param)
                : null;
        return new IWildcardType(wildCardExpression.getBoundKind(), bound);
    }

    @Override
    public IType visitVariableType(final VariableType variableType, final Object param) {
        return variableType.getInterferedType().accept(this, param);
    }
}
