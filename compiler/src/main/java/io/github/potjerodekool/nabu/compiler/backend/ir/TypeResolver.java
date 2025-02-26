package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.TodoException;
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
        throw new TodoException();
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
        final var kind = clazz.getKind() == ElementKind.INTERFACE
                ? ITypeKind.INTERFACE
                : ITypeKind.CLASS;

        return IReferenceType.create(
                kind,
                clazz.getQualifiedName(),
                types
        );
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
    public IType visitTypeNameExpression(final TypeNameExpressioTree typeNameExpression, final Object param) {
        final var className = asString(typeNameExpression);
        final var declaredType = (DeclaredType) typeNameExpression.getType();
        final var kind = declaredType.asElement().getKind() == ElementKind.INTERFACE
                ? ITypeKind.INTERFACE
                : ITypeKind.CLASS;

        return IReferenceType.create(kind, className, List.of());
    }

    private String asString(final ExpressionTree expression) {
        return switch (expression) {
            case TypeNameExpressioTree typeNameExpression -> {
                final var packageName = asString(typeNameExpression.getPackageName());
                final var className = asString(typeNameExpression.getIdenifier());
                yield packageName + "." + className;
            }
            case IdentifierTree ident -> ident.getName();
            case TypeApplyTree typeIdentifier -> typeIdentifier.getName();
            default -> "";
        };
    }

    @Override
    public IType visitDeclaredType(final DeclaredType classType, final Object param) {
        final var clazz = (TypeElement) classType.asElement();
        final List<IType> typeArguments;

        if (classType.getTypeArguments() != null) {
            typeArguments = classType.getTypeArguments().stream()
                    .map(it -> it.accept(this, param))
                    .toList();
        } else {
            typeArguments = null;
        }

        final var kind = clazz.getKind() == ElementKind.INTERFACE
                ? ITypeKind.INTERFACE
                : ITypeKind.CLASS;

        return IReferenceType.create(
                kind,
                clazz.getQualifiedName(),
                typeArguments
        );
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
            default -> throw new TodoException();
        };
    }

    @Override
    public IType visitWildcardType(final WildcardType wildcardType, final Object param) {
        final var extendsBound = wildcardType.getExtendsBound() != null
                ? wildcardType.getExtendsBound().accept(this, param)
                : null;

        final var superBound = wildcardType.getSuperBound() != null
                ? wildcardType.getSuperBound().accept(this, param) : null;

        return new IWildcardType(extendsBound, superBound);
    }

    @Override
    public IType visitWildCardExpression(final WildCardExpressionTree wildCardExpression, final Object param) {
        if (wildCardExpression.getExtendsBound() != null
            || wildCardExpression.getSuperBound() != null) {
            throw new TodoException();
        }

        final var extendsBound = wildCardExpression.getExtendsBound() != null
                ? wildCardExpression.getExtendsBound().getType().accept(this, param)
                : null;

        final var superBound = wildCardExpression.getSuperBound() != null
                ? wildCardExpression.getSuperBound().getType().accept(this, param)
                : null;

        return new IWildcardType(extendsBound, superBound);
    }
}
