package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.util.Types;

public class IsAssignableMatcher extends BooleanResultVisitor {

    private final IsSubType isSubType;
    private final Types types;

    public IsAssignableMatcher(final IsSubType delegate,
                        final Types types) {
        this.isSubType = delegate;
        this.types = types;
    }

    @Override
    public Boolean visitArrayType(final ArrayType arrayType, final TypeMirror other) {
        return isSubType.visitArrayType(arrayType, other);
    }

    @Override
    public Boolean visitDeclaredType(final DeclaredType declaredType, final TypeMirror other) {
        return isSubType.visitDeclaredType(declaredType, other);
    }

    @Override
    public Boolean visitMethodType(final ExecutableType methodType, final TypeMirror other) {
        return isSubType.visitMethodType(methodType, other);
    }

    @Override
    public Boolean visitNoType(final NoType noType, final TypeMirror other) {
        return isSubType.visitNoType(noType, other);
    }

    @Override
    public Boolean visitPrimitiveType(final PrimitiveType primitiveType, final TypeMirror other) {
        if (other instanceof PrimitiveType otherPrimitive) {
            return primitiveType.getKind() == otherPrimitive.getKind();
        } else if (other instanceof DeclaredType declaredType) {
            final var clazz = (TypeElement) declaredType.asElement();
            final var className = clazz.getQualifiedName();

            if (Constants.OBJECT.equals(className)) {
                return true;
            }

            var isAssignable = switch (primitiveType.getKind()) {
                case BOOLEAN -> Constants.BOOLEAN.equals(className);
                case CHAR -> Constants.CHARACTER.equals(className);
                case BYTE -> Constants.BYTE.equals(className);
                case SHORT -> Constants.SHORT.equals(className);
                case INT -> Constants.INTEGER.equals(className);
                case FLOAT -> Constants.FLOAT.equals(className);
                case LONG -> Constants.LONG.equals(className);
                case DOUBLE -> Constants.DOUBLE.equals(className);
                default -> false;
            };

            if (!isAssignable) {
                final var boxedElement = types.boxedClass(primitiveType);
                final var boxedType = boxedElement.asType();
                isAssignable = boxedType.accept(isSubType, other);
            }

            return isAssignable;
        }
        return false;
    }

    @Override
    public Boolean visitNullType(final NullType nullType, final TypeMirror otherType) {
        return otherType instanceof ReferenceType;
    }

    @Override
    public Boolean visitVariableType(final VariableType variableType, final TypeMirror otherType) {
        if (variableType.getInterferedType() != null) {
            return variableType.getInterferedType().accept(this, otherType);
        } else {
            return false;
        }
    }

}
