package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.tree.expression.CExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;
import io.github.potjerodekool.nabu.compiler.tree.expression.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.tree.expression.CTypeApply;
import io.github.potjerodekool.nabu.compiler.type.*;

class TypeExpressionCreator implements TypeVisitor<CExpression, Object> {

    @Override
    public CExpression visitArrayType(final ArrayType arrayType, final Object param) {
        throw new TodoException();
    }

    @Override
    public CExpression visitClassType(final ClassType classType, final Object param) {
        final var clazz = (ClassSymbol) classType.asElement();
        final var paramTypes = classType.getParameterTypes() != null
                ? classType.getParameterTypes().stream()
                .map(paramType -> paramType.accept(this, paramType))
                .toList()
                : null;

        final var typeIdentifier = new CTypeApply(
                new CIdent(clazz.getQualifiedName()),
                paramTypes
        );

        typeIdentifier.setType(classType);
        return typeIdentifier;
    }

    @Override
    public CExpression visitMethodType(final MethodType methodType, final Object param) {
        throw new TodoException();

    }

    @Override
    public CExpression visitVoidType(final VoidType voidType, final Object param) {
        throw new TodoException();

    }

    @Override
    public CExpression visitPrimitiveType(final PrimitiveType primitiveType, final Object param) {
        final var kind = switch (primitiveType.getKind()) {
            case BOOLEAN -> CPrimitiveType.Kind.BOOLEAN;
            case CHAR -> CPrimitiveType.Kind.CHAR;
            case BYTE -> CPrimitiveType.Kind.BYTE;
            case SHORT -> CPrimitiveType.Kind.SHORT;
            case INT -> CPrimitiveType.Kind.INT;
            case FLOAT -> CPrimitiveType.Kind.FLOAT;
            case LONG -> CPrimitiveType.Kind.LONG;
            case DOUBLE -> CPrimitiveType.Kind.DOUBLE;
            default -> throw new TodoException("" + primitiveType.getKind());
        };

        return new CPrimitiveType(kind);
    }

    @Override
    public CExpression visitNullType(final NullType nullType, final Object param) {
        return null;
    }

    @Override
    public CExpression visitVariableType(final VariableType variableType, final Object param) {
        return null;
    }

    @Override
    public CExpression visitWildcardType(final WildcardType wildcardType, final Object param) {
        return null;
    }

    @Override
    public CExpression visitTypeVariable(final TypeVariable typeVariable, final Object param) {
        return null;
    }
}
