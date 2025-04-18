package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.type.*;

public class TypeExpressionCreator implements TypeVisitor<ExpressionTree, Object> {

    @Override
    public ExpressionTree visitUnknownType(final TypeMirror typeMirror, final Object param) {
        throw new UnsupportedOperationException(typeMirror.getClass().getName());
    }

    @Override
    public ExpressionTree visitDeclaredType(final DeclaredType declaredType, final Object param) {
        final var clazz = (TypeElement) declaredType.asElement();
        final var paramTypes = declaredType.getTypeArguments() != null
                ? declaredType.getTypeArguments().stream()
                .map(paramType -> paramType.accept(this, paramType))
                .toList()
                : null;

        final var typeIdentifier = TreeMaker.typeApplyTree(
                IdentifierTree.create(clazz.getQualifiedName()),
                paramTypes,
                -1,
                -1
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
            default -> throw new IllegalArgumentException("Invalid primitive kind" + primitiveType.getKind());
        };

        return TreeMaker.primitiveTypeTree(kind, -1, -1);
    }

    @Override
    public ExpressionTree visitWildcardType(final WildcardType wildcardType, final Object param) {
        final var bound = wildcardType.getBound() != null
                ? wildcardType.getBound().accept(this, param)
                : null;

        return TreeMaker.wildcardExpressionTree(
                wildcardType.getBoundKind(),
                bound,
                -1,
                -1
        );
    }

    @Override
    public ExpressionTree visitVariableType(final VariableType variableType, final Object param) {
        final var variableTypeTree = TreeMaker.variableTypeTree(-1, -1);
        variableTypeTree.setType(variableType);
        return variableTypeTree;
    }
}
