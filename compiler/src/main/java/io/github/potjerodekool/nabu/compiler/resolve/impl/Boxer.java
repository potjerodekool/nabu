package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.impl.box.LongBoxer;
import io.github.potjerodekool.nabu.compiler.resolve.impl.box.ShortBoxer;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Types;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class Boxer implements TypeVisitor<ExpressionTree, ExpressionTree> {

    private static int BOX_TRACE_COUNT = 0;
    private static int BOX_DEPTH = 0;
    private static int BOX_FRAME_DEPTH = 0;
    private static int BOX_STACK_TRACE_COUNT = 0;
    private static int BOX_REPEAT_COUNT = 0;
    private static final Map<String, Integer> BOX_COUNTS = new java.util.HashMap<>();

    private final ClassElementLoader loader;
    private final Types types;
    private final MethodResolver methodResolver;
    private final LongBoxer longBoxer;
    private final ShortBoxer shortBoxer;

    private final EnumMap<TypeKind, String> primitiveTypeToBoxClassName = new EnumMap<>(TypeKind.class);
    private final EnumMap<TypeKind, String> unboxMethods = new EnumMap<>(TypeKind.class);

    public Boxer(final CompilerContext compilerContext) {
        this.loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getTypes();
        this.methodResolver = compilerContext.getMethodResolver();
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

            if (symbol == null) {
                return expressionTree;
            }

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
        if (expressionTree.getLineNumber() < 0) {
            return expressionTree;
        }

        if (otherType.getKind().isPrimitive()) {
            if (BOX_TRACE_COUNT < 6) {
                BOX_TRACE_COUNT++;
                try (final var pw = new java.io.PrintWriter(
                        new java.io.FileWriter("C:/Users/evert/AppData/Local/Temp/opencode/diag.log", true))) {
                    pw.println("[BOX] depth=" + BOX_DEPTH
                            + " decl=" + declaredType.asTypeElement().getQualifiedName()
                            + " otherKind=" + otherType.getKind()
                            + " line=" + expressionTree.getLineNumber()
                            + " col=" + expressionTree.getColumnNumber()
                            + " expr=" + expressionTree);
                } catch (java.io.IOException e) {
                    // ignore
                }
            }

            if (BOX_DEPTH > 32) {
                return expressionTree;
            }

            BOX_DEPTH++;
            try {
                return boxExpression(expressionTree, otherType.getKind());
            } finally {
                BOX_DEPTH--;
            }
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
        if (BOX_FRAME_DEPTH > 32) {
            return expression;
        }

        BOX_FRAME_DEPTH++;
        try {
            final var boxKey = className + "@" + expression.getLineNumber() + ":" + expression.getColumnNumber();
            final var boxCount = BOX_COUNTS.getOrDefault(boxKey, 0) + 1;
            BOX_COUNTS.put(boxKey, boxCount);

            if (boxCount == 3 && BOX_REPEAT_COUNT < 3) {
                BOX_REPEAT_COUNT++;
                try (final var pw = new java.io.PrintWriter(
                        new java.io.FileWriter("C:/Users/evert/AppData/Local/Temp/opencode/diag.log", true))) {
                    pw.println("[BOX-REPEAT] key=" + boxKey + " count=" + boxCount
                            + " line=" + expression.getLineNumber()
                            + " col=" + expression.getColumnNumber());
                    final var stack = Thread.currentThread().getStackTrace();
                    for (int i = 0; i < stack.length && i < 40; i++) {
                        pw.println("  " + stack[i]);
                    }
                } catch (java.io.IOException e) {
                    // ignore
                }
            }

            if (BOX_STACK_TRACE_COUNT < 2) {
                BOX_STACK_TRACE_COUNT++;
                try (final var pw = new java.io.PrintWriter(
                        new java.io.FileWriter("C:/Users/evert/AppData/Local/Temp/opencode/diag.log", true))) {
                    final var stack = Thread.currentThread().getStackTrace();
                    pw.println("[BOX-STACK] className=" + className
                            + " line=" + expression.getLineNumber()
                            + " col=" + expression.getColumnNumber());
                    for (int i = 0; i < stack.length && i < 30; i++) {
                        pw.println("  " + stack[i]);
                    }
                } catch (java.io.IOException e) {
                    // ignore
                }
            }

            if (BOX_TRACE_COUNT < 12) {
                BOX_TRACE_COUNT++;
                try (final var pw = new java.io.PrintWriter(
                        new java.io.FileWriter("C:/Users/evert/AppData/Local/Temp/opencode/diag.log", true))) {
                    pw.println("[BOX-FRAME] depth=" + BOX_FRAME_DEPTH
                            + " className=" + className
                            + " line=" + expression.getLineNumber()
                            + " col=" + expression.getColumnNumber()
                            + " expr=" + expression);
                } catch (java.io.IOException e) {
                    // ignore
                }
            }

            final var target = IdentifierTree.create(className);
        target.setSymbol(loader.loadClass(null, className));

        final var methodInvocation = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        target,
                        IdentifierTree.create("valueOf")
                ),
                List.of(),
                List.of(expression),
                -1,
                -1
        );

        methodResolver.resolveMethod(methodInvocation, null).ifPresent(methodType -> {
            methodInvocation.getMethodSelector().setType(methodType.getOwner().asType());
            methodInvocation.setMethodType(methodType);
        });

            return methodInvocation;
        } finally {
            BOX_FRAME_DEPTH--;
        }
    }

public ExpressionTree visitPrimitiveType(final ExpressionTree expressionTree,
                                         final PrimitiveType primitiveType,
                                         final TypeMirror otherType) {
        if (expressionTree.getLineNumber() < 0) {
            return expressionTree;
        }

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
        final var methodInvocation = createMethodInvocationForUnbox(expressionTree, methodName);

        methodResolver.resolveMethod(methodInvocation, null).ifPresent(methodType -> {
            methodInvocation.getMethodSelector().setType(methodType.getOwner().asType());
            methodInvocation.setMethodType(methodType);
        });

        return methodInvocation;
    }

    private MethodInvocationTree createMethodInvocationForUnbox(final ExpressionTree expressionTree,
                                                                final String methodName) {
        var methodInvocation = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        expressionTree,
                        IdentifierTree.create(methodName)
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        methodInvocation = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        expressionTree,
                        IdentifierTree.create(methodName)
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        return methodInvocation;
    }

}
