package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.type.*;

public class IsSameType extends BooleanResultVisitor {

    @Override
    public Boolean visitArrayType(final ArrayType arrayType, final TypeMirror otherType) {
        if (otherType instanceof ArrayType otherArrayType) {
            return arrayType.getComponentType().accept(
                    this,
                    otherArrayType.getComponentType()
            );
        }

        return false;
    }

    @Override
    public Boolean visitDeclaredType(final DeclaredType declaredType, final TypeMirror otherType) {
        if (otherType instanceof DeclaredType otherDeclaredType) {
            final var clazz = (TypeElement) declaredType.asElement();
            final var otherClass = (TypeElement) otherDeclaredType.asElement();

            if (!clazz.getQualifiedName().equals(otherClass.getQualifiedName())) {
                return false;
            }

            if (getParameterTypeCount(declaredType) != getParameterTypeCount(otherDeclaredType)) {
                return false;
            }

            final var typeArguments = declaredType.getTypeArguments();

            if (typeArguments == null) {
                return true;
            }

            final var otherParameterTypes = otherDeclaredType.getTypeArguments();

            for (int i = 0; i < typeArguments.size(); i++) {
                if (!typeArguments.get(i).accept(this, otherParameterTypes.get(i))) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    /**
     * @return Return the number of parameter types.
     * Returns -1 in case of parameterTypes is null to distinct it
     * from an empty parameter type list.
     */
    private int getParameterTypeCount(final DeclaredType declaredType) {
        return declaredType.getTypeArguments() != null
                ? declaredType.getTypeArguments().size()
                : -1;
    }

    @Override
    public Boolean visitMethodType(final ExecutableType methodType, final TypeMirror otherType) {
        if (otherType instanceof ExecutableType otherMethodType) {
            final var argumentTypes = methodType.getParameterTypes();
            final var otherArgumentTypes = otherMethodType.getParameterTypes();

            if (argumentTypes.size() != otherArgumentTypes.size()) {
                return false;
            }

            for (int i = 0; i < argumentTypes.size(); i++) {
                final var argumentType = argumentTypes.get(i);
                if (!argumentType.accept(this, otherArgumentTypes.get(i))) {
                    return false;
                }
            }

            return methodType.getReturnType().accept(this, otherMethodType.getReturnType());
        }

        return false;
    }

    @Override
    public Boolean visitNoType(final NoType noType, final TypeMirror otherType) {
        return otherType instanceof VoidType;
    }

    @Override
    public Boolean visitPrimitiveType(final PrimitiveType primitiveType, final TypeMirror otherType) {
        return otherType instanceof PrimitiveType otherPrimitiveType
                && primitiveType.getKind() == otherPrimitiveType.getKind();
    }

    @Override
    public Boolean visitNullType(final NullType nullType, final TypeMirror otherType) {
        return otherType instanceof NullType;
    }

    @Override
    public Boolean visitVariableType(final VariableType variableType, final TypeMirror otherType) {
        return otherType instanceof VariableType;
    }

    @Override
    public Boolean visitWildcardType(final WildcardType wildcardType, final TypeMirror otherType) {
        if (otherType instanceof WildcardType otherWildcardType) {
            if (wildcardType.getExtendsBound() != null) {
                return wildcardType.getExtendsBound().accept(this, otherWildcardType.getExtendsBound());
            } else if (wildcardType.getSuperBound() != null) {
                return wildcardType.getSuperBound().accept(this, otherWildcardType.getSuperBound());
            } else {
                return otherWildcardType.getExtendsBound() == null
                        && otherWildcardType.getSuperBound() == null;
            }
        }

        return false;
    }

    @Override
    public Boolean visitTypeVariable(final TypeVariable typeVariable, final TypeMirror otherType) {
        if (otherType instanceof TypeVariable otherTypeVariable) {
            return typeVariable.asElement().equals(otherTypeVariable.asElement());
        }

        return false;
    }

}
