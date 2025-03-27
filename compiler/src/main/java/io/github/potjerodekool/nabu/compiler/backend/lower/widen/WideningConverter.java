package io.github.potjerodekool.nabu.compiler.backend.lower.widen;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.lower.ExpressionConverter;
import io.github.potjerodekool.nabu.compiler.resolve.TreeUtils;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.util.Types;

import java.util.Map;
import java.util.Set;

public class WideningConverter implements ExpressionConverter {

    private final Types types;
    private final IntWidener intWidener;
    private final Map<TypeKind, Set<TypeKind>> allowedPrimitiveConversions =
            Map.ofEntries(
                    createPrimitiveConversions(TypeKind.BYTE, Set.of(TypeKind.SHORT, TypeKind.INT, TypeKind.LONG, TypeKind.FLOAT, TypeKind.DOUBLE)),
                    createPrimitiveConversions(TypeKind.SHORT, Set.of(TypeKind.INT, TypeKind.LONG, TypeKind.FLOAT, TypeKind.DOUBLE)),
                    createPrimitiveConversions(TypeKind.CHAR, Set.of(TypeKind.INT, TypeKind.LONG, TypeKind.FLOAT, TypeKind.DOUBLE)),
                    createPrimitiveConversions(TypeKind.INT, Set.of(TypeKind.LONG, TypeKind.FLOAT, TypeKind.DOUBLE)),
                    createPrimitiveConversions(TypeKind.LONG, Set.of(TypeKind.FLOAT, TypeKind.DOUBLE)),
                    createPrimitiveConversions(TypeKind.FLOAT, Set.of(TypeKind.DOUBLE))
            );

    public WideningConverter(final Types types) {
        this.types = types;
        this.intWidener = new IntWidener(types);
    }

    private Map.Entry<TypeKind, Set<TypeKind>> createPrimitiveConversions(final TypeKind kind,
                                                                          final Set<TypeKind> targets) {
        return Map.entry(kind, targets);
    }

    @Override
    public ExpressionTree convert(final ExpressionTree left, final ExpressionTree right) {
        final var leftType = TreeUtils.typeOf(left);
        final var rightType = TreeUtils.typeOf(right);

        if (leftType instanceof DeclaredType leftClassType) {
            return visitDeclaredType(left, leftClassType, rightType);
        } else if (leftType instanceof PrimitiveType leftPrimitiveType) {
            return visitPrimitiveType(left, leftPrimitiveType, rightType);
        } else {
            return left;
        }
    }

    private ExpressionTree visitDeclaredType(final ExpressionTree expressionTree,
                                             final DeclaredType declaredType,
                                             final TypeMirror otherType) {
        if (types.isBoxType(declaredType)) {
            if (otherType instanceof PrimitiveType otherPrimitiveType) {
                final var primitiveKind = types.unboxedType(declaredType).getKind();
                final var otherPrimitiveKind = otherPrimitiveType.getKind();

                if (primitiveKind == otherPrimitiveKind) {
                    return expressionTree;
                }

                if (primitiveKind == TypeKind.LONG) {
                    if (otherPrimitiveKind == TypeKind.INT) {
                        return expressionTree;
                    }
                } else if (primitiveKind == TypeKind.INT) {
                    if (otherPrimitiveKind == TypeKind.LONG) {
                        throw new TodoException();
                    }
                } else if (primitiveKind == TypeKind.SHORT) {
                    if (otherPrimitiveKind == TypeKind.INT) {
                        return expressionTree;
                    }
                }

                throw new TodoException();
            }
        }

        return expressionTree;
    }

    private ExpressionTree visitPrimitiveType(final ExpressionTree expressionTree,
                                              final PrimitiveType primitiveType,
                                              final TypeMirror otherType) {
        if (otherType instanceof PrimitiveType otherPrimitiveType) {
            final var primitiveTypeKind = primitiveType.getKind();
            final var otherPrimitiveTypeKind = otherPrimitiveType.getKind();

            if (primitiveTypeKind == otherPrimitiveTypeKind) {
                return expressionTree;
            } else {
                return widenPrimitiveExpression(
                        expressionTree,
                        primitiveTypeKind,
                        otherPrimitiveTypeKind
                );
            }
        } else if (otherType instanceof DeclaredType declaredType) {
            if (types.isBoxType(declaredType)) {
                final var primitiveTypeKind = primitiveType.getKind();
                final var otherPrimitiveType = types.unboxedType(declaredType);
                final var otherPrimitiveTypeKind = otherPrimitiveType.getKind();

                if (primitiveTypeKind != otherPrimitiveTypeKind) {
                    if (primitiveTypeKind == TypeKind.INT) {
                        if (otherPrimitiveTypeKind == TypeKind.LONG) {
                            return intWidener.widenToLong(expressionTree);
                        }
                    }
                }
            }
        }

        return expressionTree;
    }

    private ExpressionTree widenPrimitiveExpression(final ExpressionTree expressionTree,
                                                    final TypeKind sourceType,
                                                    final TypeKind targetType) {
        final var allowedTargets = allowedPrimitiveConversions.get(sourceType);

        if (allowedTargets == null || !allowedTargets.contains(targetType)) {
            return expressionTree;
        }

        if (expressionTree instanceof LiteralExpressionTree literalExpressionTree) {
            final var literal = literalExpressionTree.getLiteral();
            final Object newLiteral;

            if (literal instanceof Number numberLiteral) {
                if (targetType == TypeKind.SHORT) {
                    newLiteral = numberLiteral.shortValue();
                } else if (targetType == TypeKind.INT) {
                    newLiteral = numberLiteral.intValue();
                } else if (targetType == TypeKind.LONG) {
                    newLiteral = numberLiteral.longValue();
                } else if (targetType == TypeKind.FLOAT) {
                    newLiteral = numberLiteral.floatValue();
                } else if (targetType == TypeKind.DOUBLE) {
                    newLiteral = numberLiteral.doubleValue();
                } else {
                    throw new TodoException();
                }
            } else {
                final Integer intLiteral = Integer.valueOf((char) literal);

                if (targetType == TypeKind.INT) {
                    newLiteral = intLiteral;
                } else if (targetType == TypeKind.LONG) {
                    newLiteral = intLiteral.longValue();
                } else if (targetType == TypeKind.FLOAT) {
                    newLiteral = (float) intLiteral;
                } else if (targetType == TypeKind.DOUBLE) {
                    newLiteral = (double) intLiteral;
                } else {
                    throw new TodoException();
                }
            }

            final var newType = types.getPrimitiveType(targetType);

            return literalExpressionTree.builder()
                    .literal(newLiteral)
                    .type(newType)
                    .build();
        } else {
            return expressionTree;
        }
    }

}
