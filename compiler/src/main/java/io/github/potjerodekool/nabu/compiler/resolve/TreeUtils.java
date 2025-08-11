package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.VariableType;

public final class TreeUtils {

    private TreeUtils() {
    }

    public static TypeMirror typeOf(final Tree tree) {
        return switch (tree) {
            case FieldAccessExpressionTree fieldAccessExpression -> typeOf(fieldAccessExpression.getField());
            case IdentifierTree identifier -> identifier.getType() != null
                    ? identifier.getType()
                    : typeOf(identifier.getSymbol());
            case CastExpressionTree asExpression -> typeOf(asExpression.getTargetType());
            case MethodInvocationTree methodInvocation -> methodInvocation.getMethodType().getReturnType();
            case VariableTypeTree variableType -> {
                final var type = variableType.getType();
                yield type instanceof VariableType varType && varType.getInterferedType() != null
                        ? varType.getInterferedType()
                        : variableType.getType();
            }
            case NewClassExpression newClassExpression -> typeOf(newClassExpression.getName());
            case ExpressionTree expressionTree -> expressionTree.getType();
            default -> throw new UnsupportedOperationException(tree.getClass().getName());
        };
    }

    public static TypeMirror typeOf(final Element element) {
        if (element == null || !element.exists()) {
            return null;
        }

        if (element instanceof VariableElement variableElement) {
            final var varType = variableElement.asType();

            if (varType instanceof VariableType variableType && variableType.getInterferedType() != null) {
                return variableType.getInterferedType();
            } else {
                return varType;
            }
        } else {
            return element.asType();
        }
    }

    public static Element getSymbol(final ExpressionTree expression) {
        return switch (expression) {
            case FieldAccessExpressionTree fieldAccessExpressionTree -> getSymbol(fieldAccessExpressionTree.getField());
            case MethodInvocationTree methodInvocationTree -> {
                final var methodSelector = methodInvocationTree.getMethodSelector();

                if (methodSelector instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
                    yield getSymbol(fieldAccessExpressionTree.getSelected());
                } else {
                    yield null;
                }
            }
            default -> expression.getSymbol();
        };
    }

    public static String getClassName(final ExpressionTree expressionTree) {
        switch (expressionTree) {
            case IdentifierTree identifierTree -> {
                return identifierTree.getName();
            }
            case FieldAccessExpressionTree fieldAccessExpressionTree -> {
                final var selectedName = getClassName(fieldAccessExpressionTree.getSelected());
                final var fieldName = getClassName(fieldAccessExpressionTree.getField());
                return selectedName + "." + fieldName;
            }
            case AnnotatedTypeTree annotatedTypeTree -> {
                return getClassName(annotatedTypeTree.getClazz());
            }
            case TypeNameExpressionTree typeNameExpression -> {
                final var packageName = getClassName(typeNameExpression.getPackageName());
                final var className = getClassName(typeNameExpression.getIdenifier());
                return packageName + "." + className;
            }
            case TypeApplyTree typeApplyTree -> {
                return getClassName(typeApplyTree.getClazz());
            }
            default -> throw new IllegalArgumentException(expressionTree.getClass().getName());
        }
    }
}
