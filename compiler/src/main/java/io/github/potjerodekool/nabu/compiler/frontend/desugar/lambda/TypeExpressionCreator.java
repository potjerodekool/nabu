package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.type.*;

class TypeExpressionCreator implements TypeVisitor<ExpressionTree, Object> {

    @Override
    public ExpressionTree visitUnknownType(final TypeMirror typeMirror, final Object param) {
        throw new TodoException();
    }

    @Override
    public ExpressionTree visitDeclaredType(final DeclaredType declaredType, final Object param) {
        final var clazz = (TypeElement) declaredType.asElement();
        final var paramTypes = declaredType.getTypeArguments() != null
                ? declaredType.getTypeArguments().stream()
                .map(paramType -> paramType.accept(this, paramType))
                .toList()
                : null;

        final var typeIdentifier = new TypeApplyTree(
                new IdentifierTree(clazz.getQualifiedName()),
                paramTypes
        );

        typeIdentifier.setType(declaredType);
        return typeIdentifier;
    }

    @Override
    public ExpressionTree visitPrimitiveType(final PrimitiveType primitiveType, final Object param) {
        final var kind = switch (primitiveType.getKind()) {
            case BOOLEAN -> PrimitiveTypeTree.Kind.BOOLEAN;
            case CHAR -> PrimitiveTypeTree.Kind.CHAR;
            case BYTE -> PrimitiveTypeTree.Kind.BYTE;
            case SHORT -> PrimitiveTypeTree.Kind.SHORT;
            case INT -> PrimitiveTypeTree.Kind.INT;
            case FLOAT -> PrimitiveTypeTree.Kind.FLOAT;
            case LONG -> PrimitiveTypeTree.Kind.LONG;
            case DOUBLE -> PrimitiveTypeTree.Kind.DOUBLE;
            default -> throw new TodoException("" + primitiveType.getKind());
        };

        return new PrimitiveTypeTree(kind);
    }

    @Override
    public ExpressionTree visitWildcardType(final WildcardType wildcardType, final Object param) {
        if (wildcardType.getExtendsBound() != null
                || wildcardType.getSuperBound() != null) {
            throw new TodoException();
        }

        return new WildCardExpressionTree(
                null,
                null
        );
    }
}
