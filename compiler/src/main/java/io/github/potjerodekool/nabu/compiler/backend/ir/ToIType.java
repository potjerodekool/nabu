package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;
import io.github.potjerodekool.nabu.compiler.type.*;

class ToIType implements TypeVisitor<IType, Object> {

    @Override
    public IType visitArrayType(final ArrayType arrayType, final Object param) {
        throw new TodoException();
    }

    @Override
    public IType visitClassType(final ClassType classType, final Object param) {
        final var clazz = (ClassSymbol) classType.asElement();
        final var name = clazz.getQualifiedName();
        final var typeParams = classType.getParameterTypes() != null
                ? classType.getParameterTypes().stream()
                .map(typeParam -> typeParam.accept(this, param))
                .toList()
                : null;

        return IReferenceType.create(
                name,
                typeParams
        );
    }

    @Override
    public IType visitMethodType(final MethodType methodType, final Object param) {
        throw new TodoException();
    }

    @Override
    public IType visitVoidType(final VoidType voidType, final Object param) {
        return IPrimitiveType.VOID;
    }

    @Override
    public IType visitPrimitiveType(final PrimitiveType primitiveType, final Object param) {
        return switch (primitiveType.getKind()) {
            case BOOLEAN -> IPrimitiveType.BOOLEAN;
            case CHAR -> IPrimitiveType.CHAR;
            case BYTE -> IPrimitiveType.BYTE;
            case SHORT -> IPrimitiveType.SHORT;
            case INT -> IPrimitiveType.INT;
            case FLOAT -> IPrimitiveType.FLOAT;
            case LONG -> IPrimitiveType.LONG;
            case DOUBLE -> IPrimitiveType.DOUBLE;
            default -> throw new TodoException();
        };
    }

    @Override
    public IType visitNullType(final NullType nullType, final Object param) {
        return IReferenceType.NULL;
    }

    @Override
    public IType visitVariableType(final VariableType variableType, final Object param) {
        return variableType.getInterferedType().accept(this, param);
    }

    @Override
    public IType visitWildcardType(final WildcardType wildcardType, final Object param) {
       throw new TodoException();
    }

    @Override
    public IType visitTypeVariable(final TypeVariable typeVariable, final Object param) {
        throw new TodoException();
    }
}
