package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.VariableType;

public final class TreeUtils {

    private TreeUtils() {
    }

    public static TypeMirror resolveType(final Tree tree) {
        return switch (tree) {
            case FieldAccessExpressioTree fieldAccessExpression -> resolveType(fieldAccessExpression.getField());
            case IdentifierTree ident -> {
                if (ident.getType() != null) {
                    yield ident.getType();
                } else {
                    yield resolveType(ident.getSymbol());
                }
            }
            case CastExpressionTree asExpression -> resolveType(asExpression.getTargetType());
            case MethodInvocationTree methodInvocation ->  methodInvocation.getMethodType().getReturnType();
            case VariableTypeTree variableType -> {
                final var type = variableType.getType();

                if (type instanceof VariableType varType && varType.getInterferedType() != null) {
                    yield varType.getInterferedType();
                } else {
                    yield variableType.getType();
                }
            }
            case ExpressionTree expressionTree -> expressionTree.getType();
            default -> throw new TodoException(tree.getClass().getName());
        };
    }

    public static TypeMirror resolveType(final Element element) {
        if (element == null) {
            return null;
        }

        if (element instanceof VariableElement variableElement) {
            final var varType = variableElement.asType();

            if (varType instanceof VariableType variableType && variableType.getInterferedType() != null) {
                return variableType.getInterferedType();
            } else {
                return varType;
            }
        }

        throw new TodoException(element.getClass().getName());
    }

    public static Element getSymbol(final ExpressionTree expression) {
        return switch (expression) {
            case IdentifierTree ignored -> expression.getSymbol();
            case FieldAccessExpressioTree fieldAccessExpressioTree -> getSymbol(fieldAccessExpressioTree.getField());
            case MethodInvocationTree methodInvocationTree -> getSymbol(methodInvocationTree.getTarget());
            default -> throw new TodoException();
        };
    }
}
