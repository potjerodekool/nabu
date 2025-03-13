package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.box.LongBoxer;
import io.github.potjerodekool.nabu.compiler.resolve.box.ShortBoxer;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.List;

public class Boxer implements TypeVisitor<ExpressionTree, ExpressionTree> {

    private final ClassElementLoader loader;
    private final Types types;
    private final MethodResolver methodResolver;
    private final LongBoxer longBoxer;
    private final ShortBoxer shortBoxer;

    public Boxer(final ClassElementLoader loader,
                 final MethodResolver methodResolver) {
        this.loader = loader;
        this.types = loader.getTypes();
        this.methodResolver = methodResolver;
        this.longBoxer = new LongBoxer(methodResolver);
        this.shortBoxer = new ShortBoxer(methodResolver);
    }

    @Override
    public ExpressionTree visitUnknownType(final TypeMirror typeMirror, final ExpressionTree expressionTree) {
        return expressionTree;
    }


    @Override
    public ExpressionTree visitDeclaredType(final DeclaredType declaredType, final ExpressionTree expressionTree) {
        if (expressionTree instanceof LiteralExpressionTree literalExpression) {
            return boxIfNeeded(literalExpression, declaredType, literalExpression.getType());
        } else if (expressionTree instanceof IdentifierTree identifier) {
            final var symbol = identifier.getSymbol();
            final var varType = symbol.asType();
            return boxIfNeeded(identifier, declaredType, varType);
        } else {
            return expressionTree;
        }
    }

    public ExpressionTree boxIfNeeded(final ExpressionTree expressionTree, final TypeMirror leftType, final TypeMirror rightType) {
        if (leftType instanceof DeclaredType declaredType) {
            return visitDeclaredType(expressionTree, declaredType, rightType);
        } else if (leftType instanceof PrimitiveType primitiveType) {
            return visitPrimitiveType(expressionTree, primitiveType, rightType);
        } else {
            return expressionTree;
        }
    }

    public ExpressionTree visitDeclaredType(final ExpressionTree expressionTree,
                                            final DeclaredType declaredType,
                                            final TypeMirror otherType) {
        if (otherType instanceof PrimitiveType primitiveType) {
            return switch (primitiveType.getKind()) {
                case BOOLEAN -> boxBoolean(expressionTree);
                case INT -> boxInteger(expressionTree);
                case LONG -> boxLong(expressionTree);
                default -> throw new TodoException();
            };
        } else if (!types.isBoxType(declaredType)) {
            return expressionTree;
        }

        final var clazz = (TypeElement) declaredType.asElement();
        final var className = clazz.getQualifiedName();

        if (Constants.LONG.equals(className)) {
            return longBoxer.boxer(expressionTree, otherType);
        } else if (Constants.SHORT.equals(className)) {
            return shortBoxer.boxer(expressionTree, otherType);
        }

        return expressionTree;
    }

    private ExpressionTree boxBoolean(final ExpressionTree expression) {
        return box(expression, "java.lang.Boolean");
    }

    private ExpressionTree boxInteger(final ExpressionTree expression) {
        return box(expression, "java.lang.Integer");
    }

    private ExpressionTree boxLong(final ExpressionTree expression) {
        return box(expression, "java.lang.Long");
    }

    private ExpressionTree box(final ExpressionTree expression,
                               final String className) {
        final var target = IdentifierTree.create(className);
        target.setSymbol(loader.loadClass(className));

        final var methodInvocation = TreeMaker.methodInvocationTree(
                target,
                IdentifierTree.create("valueOf"),
                List.of(),
                List.of(expression),
                -1,
                -1
        );

        final var methodType = methodResolver.resolveMethod(methodInvocation, null);
        methodInvocation.setMethodType(methodType);

        return methodInvocation;
    }

    public ExpressionTree visitPrimitiveType(final ExpressionTree expressionTree,
                                             final PrimitiveType primitiveType,
                                             final TypeMirror otherType) {
        if (otherType instanceof DeclaredType dt) {
            return switch (primitiveType.getKind()) {
                case BOOLEAN -> boxBoolean(expressionTree);
                case INT -> toPrimitiveInteger(expressionTree, dt);
                case LONG -> boxLong(expressionTree);
                default -> throw new TodoException("" + primitiveType.getKind());
            };
        } else if (otherType instanceof PrimitiveType otherPrimitiveType) {
            if (primitiveType.getKind() == otherPrimitiveType.getKind()) {
                return expressionTree;
            }

            return expressionTree;
        }

        return expressionTree;
    }

    private ExpressionTree toPrimitiveInteger(final ExpressionTree expression,
                                              final DeclaredType dt) {
        final var clazz = (TypeElement) dt.asElement();
        if (Constants.INTEGER.equals(clazz.getQualifiedName())) {
            final var methodInvocation = TreeMaker.methodInvocationTree(
                    expression,
                    IdentifierTree.create("intValue"),
                    List.of(),
                    List.of(),
                    -1,
                    -1
            );

            final var methodType = methodResolver.resolveMethod(methodInvocation, null);
            methodInvocation.setMethodType(methodType);
            return methodInvocation;
        } else {
            throw new TodoException();
        }
    }

    @Override
    public ExpressionTree visitPrimitiveType(final PrimitiveType primitiveType,
                                             final ExpressionTree expressionTree) {
        final var expressionType = getTypeOf(expressionTree);

        if (expressionType instanceof DeclaredType) {
            return switch (primitiveType.getKind()) {
                case BOOLEAN -> unbox(expressionTree, "booleanValue");
                case CHAR -> unbox(expressionTree, "charValue");
                case BYTE -> unbox(expressionTree, "byteValue");
                case SHORT -> unbox(expressionTree, "shortValue");
                case INT -> unbox(expressionTree, "intValue");
                case FLOAT -> unbox(expressionTree, "floatValue");
                case LONG -> unbox(expressionTree, "longValue");
                case DOUBLE -> unbox(expressionTree, "doubleValue");
                default -> throw new TodoException("" + primitiveType.getKind());
            };
        } else {
            return expressionTree;
        }
    }

    private TypeMirror getTypeOf(final ExpressionTree expressionTree) {
        final TypeMirror type;

        if (expressionTree.getType() != null) {
            type = expressionTree.getType();
        } else if (expressionTree.getSymbol() != null) {
            type = expressionTree.getSymbol().asType();
        } else {
            type = null;
        }

        if (type instanceof VariableType variableType) {
            return variableType.getInterferedType();
        } else {
            return type;
        }
    }

    public ExpressionTree unbox(final ExpressionTree expressionTree,
                                final String methodName) {
        var methodInvocation = TreeMaker.methodInvocationTree(
                expressionTree,
                IdentifierTree.create(methodName),
                List.of(),
                List.of(),
                -1,
                -1
        );

        final var methodType = methodResolver.resolveMethod(methodInvocation, null);

        methodInvocation = TreeMaker.methodInvocationTree(
                expressionTree,
                IdentifierTree.create(methodName),
                List.of(),
                List.of(),
                -1,
                -1
        );
        methodInvocation.setMethodType(methodType);

        return methodInvocation;
    }

}
