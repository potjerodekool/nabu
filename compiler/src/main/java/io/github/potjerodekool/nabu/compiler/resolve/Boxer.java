package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.box.LongBoxer;
import io.github.potjerodekool.nabu.compiler.resolve.box.ShortBoxer;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.util.Types;

import java.util.EnumMap;
import java.util.List;

public class Boxer implements TypeVisitor<ExpressionTree, ExpressionTree> {

    private final ClassElementLoader loader;
    private final Types types;
    private final MethodResolver methodResolver;
    private final LongBoxer longBoxer;
    private final ShortBoxer shortBoxer;

    private final EnumMap<TypeKind, String> primitiveTypeToBoxClassName = new EnumMap<>(TypeKind.class);
    private final EnumMap<TypeKind, String> unboxMethods = new EnumMap<>(TypeKind.class);

    public Boxer(final ClassElementLoader loader,
                 final MethodResolver methodResolver) {
        this.loader = loader;
        this.types = loader.getTypes();
        this.methodResolver = methodResolver;
        this.longBoxer = new LongBoxer(methodResolver);
        this.shortBoxer = new ShortBoxer(methodResolver);

        primitiveTypeToBoxClassName.put(TypeKind.BOOLEAN, Constants.BOOLEAN);
        primitiveTypeToBoxClassName.put(TypeKind.CHAR, Constants.CHARACTER);
        primitiveTypeToBoxClassName.put(TypeKind.BYTE, Constants.BYTE);
        primitiveTypeToBoxClassName.put(TypeKind.SHORT, Constants.SHORT);
        primitiveTypeToBoxClassName.put(TypeKind.INT, Constants.INTEGER);
        primitiveTypeToBoxClassName.put(TypeKind.LONG, Constants.LONG);
        primitiveTypeToBoxClassName.put(TypeKind.FLOAT, Constants.FLOAT);
        primitiveTypeToBoxClassName.put(TypeKind.DOUBLE, Constants.DOUBLE);

        unboxMethods.put(TypeKind.BOOLEAN, "booleanValue");
        unboxMethods.put(TypeKind.CHAR, "charValue");
        unboxMethods.put(TypeKind.BYTE, "byteValue");
        unboxMethods.put(TypeKind.SHORT, "shortValue");
        unboxMethods.put(TypeKind.INT, "intValue");
        unboxMethods.put(TypeKind.LONG, "longValue");
        unboxMethods.put(TypeKind.FLOAT, "floatValue");
        unboxMethods.put(TypeKind.DOUBLE, "doubleValue");
    }

    private ExpressionTree boxExpression(final ExpressionTree expressionTree,
                                         final TypeKind typeKind) {
        primitiveTypeCheck(typeKind);
        final var className = primitiveTypeToBoxClassName.get(typeKind);
        return box(expressionTree, className);
    }

    private void primitiveTypeCheck(final TypeKind typeKind) {
        if (typeKind == null || !typeKind.isPrimitive()) {
            throw new IllegalArgumentException("No a primitive type" + typeKind);
        }
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
            return boxExpression(expressionTree, primitiveType.getKind());
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

    private ExpressionTree box(final ExpressionTree expression,
                               final String className) {
        final var target = IdentifierTree.create(className);
        target.setSymbol(loader.loadClass(null, className));

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
        if (otherType instanceof DeclaredType) {
            return boxExpression(expressionTree, primitiveType.getKind());
        } else if (otherType instanceof PrimitiveType otherPrimitiveType) {
            if (primitiveType.getKind() == otherPrimitiveType.getKind()) {
                return expressionTree;
            }
            return expressionTree;
        }

        return expressionTree;
    }

    @Override
    public ExpressionTree visitPrimitiveType(final PrimitiveType primitiveType,
                                             final ExpressionTree expressionTree) {
        final var expressionType = getTypeOf(expressionTree);

        if (expressionType instanceof DeclaredType) {
            primitiveTypeCheck(primitiveType.getKind());
            final var methodName = unboxMethods.get(primitiveType.getKind());
            return unbox(expressionTree, methodName);
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
