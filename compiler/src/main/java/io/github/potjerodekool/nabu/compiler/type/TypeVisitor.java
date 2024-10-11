package io.github.potjerodekool.nabu.compiler.type;

public interface TypeVisitor<R, P> {

    R visitArrayType(ArrayType arrayType,
                     P param);

    R visitClassType(ClassType classType,
                     P param);

    R visitMethodType(MethodType methodType,
                      P param);

    R visitVoidType(VoidType voidType,
                    P param);

    R visitPrimitiveType(PrimitiveType primitiveType,
                         P param);

    R visitNullType(NullType nullType, P param);

    R visitVariableType(VariableType variableType, P param);

    R visitWildcardType(WildcardType wildcardType, P param);

    R visitTypeVariable(TypeVariable typeVariable, P param);
}
