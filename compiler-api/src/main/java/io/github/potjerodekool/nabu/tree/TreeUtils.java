package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.VariableType;
import io.github.potjerodekool.nabu.util.Types;

/**
 * Utility methods for working with trees.
 */
public final class TreeUtils {

    private final Types types;

    public TreeUtils(final Types types) {
        this.types = types;
    }

    /**
     * @param tree A tree.
     * @return Returns the type of the tree.
     */
    public TypeMirror typeOf(final Tree tree) {
        return switch (tree) {
            case FieldAccessExpressionTree fieldAccessExpression -> typeOf(fieldAccessExpression.getField());
            case IdentifierTree identifier -> identifier.getType() != null
                    ? identifier.getType()
                    : typeOf(identifier.getSymbol());
            case CastExpressionTree asExpression -> typeOf(asExpression.getTargetType());
            case MethodInvocationTree methodInvocation -> {
                final var methodType = methodInvocation.getMethodType();

                if (methodType != null) {
                    yield methodType.getReturnType();
                } else {
                    yield types.getUnknownType();
                }
            }
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

    /**
     * @param element An element.
     * @return Returns the type of the element.
     */
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

    /**
     * @param expression An expression.
     * @return Returns the symbol of the expression.
     */
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

    /**
     * @param expressionTree A expression.
     * @return Returns the class name of the expression.
     */
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
                final var className = getClassName(typeNameExpression.getIdentifier());
                return packageName + "." + className;
            }
            case TypeApplyTree typeApplyTree -> {
                return getClassName(typeApplyTree.getClazz());
            }
            default -> throw new IllegalArgumentException(expressionTree.getClass().getName());
        }
    }
}
