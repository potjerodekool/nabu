package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.type.immutable.*;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableArrayType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableVariableType;

import java.util.List;

public class Types {

    private final SymbolTable symbolTable;

    private final SubTypeMatcher subTypeMatcher = new SubTypeMatcher();

    private final IsAssignableMatcher isAssignableMatcher = new IsAssignableMatcher(
            subTypeMatcher,
            this
    );

    private final SameTypeMatcher sameTypeMatcher = new SameTypeMatcher();

    public Types(final SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    public TypeElement boxedClass(final PrimitiveType p) {
        final var className = switch (p.getKind()) {
            case BOOLEAN -> Constants.BOOLEAN;
            case LONG -> Constants.LONG;
            case CHAR -> Constants.CHARACTER;
            case INT -> Constants.INTEGER;
            case BYTE -> Constants.BYTE;
            case DOUBLE -> Constants.DOUBLE;
            case FLOAT -> Constants.FLOAT;
            case SHORT -> Constants.SHORT;
            default -> null;
        };

        if (className == null) {
            throw new IllegalArgumentException("Not a primitive type " + p.getKind());
        }

        return symbolTable.getClassSymbol(ClassUtils.toInternalName(className));
    }

    public TypeMirror getVoidType() {
        return new ImmutableVoidType();
    }

    public TypeMirror getNullType() {
        return new ImmutableNullType();
    }

    public TypeMirror getPrimitiveType(final TypeKind kind) {
        return new ImmutablePrimitiveType(kind);
    }

    public DeclaredType getDeclaredType(final TypeElement typeElem,
                                        final TypeMirror... typeArgs) {
        var classType = new MutableClassType(
                typeElem,
                List.of(typeArgs)
        );

        if (typeElem.getNestingKind() == NestingKind.MEMBER) {
            if (typeElem.getEnclosingElement() instanceof PackageElement) {
                throw new IllegalStateException();
            }

            final var outerClass = (TypeElement) typeElem.getEnclosingElement();
            final var outerType = (ClassType) getDeclaredType(outerClass);

            classType.setOuterType(outerType);
        }

        return classType;
    }

    public ArrayType getArrayType(final TypeMirror componentType) {
        return new MutableArrayType(componentType);
    }

    public boolean isSubType(final TypeMirror typeMirrorA,
                             final TypeMirror typeMirrorB) {
        if (typeMirrorA == null) {
            return false;
        }

        return typeMirrorA.accept(subTypeMatcher, typeMirrorB);
    }

    public boolean isAssignable(final TypeMirror typeMirrorA,
                                final TypeMirror typeMirrorB) {
        if (typeMirrorA == null) {
            return false;
        }

        return typeMirrorA.accept(isAssignableMatcher, typeMirrorB);
    }

    public TypeMirror getErrorType(final String className) {
        return new ImmutableErrorType(className);
    }

    public TypeMirror getVarType() {
        return new MutableVariableType();
    }

    public boolean isSameType(final TypeMirror typeA, final TypeMirror typeB) {
        return typeA.accept(sameTypeMatcher, typeB);
    }
}

class SubTypeMatcher implements TypeVisitor<Boolean, TypeMirror> {

    @Override
    public Boolean visitArrayType(final ArrayType arrayType, final TypeMirror param) {
        return false;
    }

    @Override
    public Boolean visitClassType(final ClassType classType,
                                  final TypeMirror otherType) {
        if (classType instanceof ErrorType
            || otherType instanceof ErrorType) {
            return false;
        }

        if (otherType instanceof ClassType otherClassType) {
            final var clazz = (ClassSymbol) classType.asElement();
            final var otherClass = (ClassSymbol) otherClassType.asElement();

            if (clazz.getQualifiedName().equals(otherClass.getQualifiedName())) {
                return true;
            }

            final var interfaceMatch = clazz.getInterfaces().stream()
                    .anyMatch(interfaceType -> interfaceType.accept(this, otherType));

            if (interfaceMatch) {
                return true;
            }

            if (clazz.getSuperType() != null) {
                return clazz.getSuperType().accept(this, otherType);
            } else {
                return false;
            }
        }

        return false;
    }

    @Override
    public Boolean visitMethodType(final MethodType methodType, final TypeMirror param) {
        return false;
    }

    @Override
    public Boolean visitVoidType(final VoidType voidType, final TypeMirror param) {
        return false;
    }

    @Override
    public Boolean visitPrimitiveType(final PrimitiveType primitiveType, final TypeMirror param) {
        return false;
    }

    @Override
    public Boolean visitNullType(final NullType nullType, final TypeMirror param) {
        return false;
    }

    @Override
    public Boolean visitVariableType(final VariableType variableType, final TypeMirror otherType) {
        final var interferedType = variableType.getInterferedType();
        return interferedType.accept(this, otherType);
    }

    @Override
    public Boolean visitWildcardType(final WildcardType wildcardType, final TypeMirror otherType) {
        if (otherType instanceof WildcardType otherWildcardType) {
            if (wildcardType.getExtendsBound() != null) {
                return wildcardType.getExtendsBound().accept(this, otherWildcardType.getExtendsBound());
            } else if (wildcardType.getSuperBound() != null) {
                return wildcardType.getSuperBound().accept(this, otherWildcardType.getSuperBound());
            }
        }

        return false;
    }

    @Override
    public Boolean visitTypeVariable(final TypeVariable typeVariable, final TypeMirror param) {
        return false;
    }

}

class IsAssignableMatcher implements TypeVisitor<Boolean, TypeMirror> {

    private final SubTypeMatcher subTypeMatcher;
    private final Types types;

    IsAssignableMatcher(final SubTypeMatcher delegate,
                        final Types types) {
        this.subTypeMatcher = delegate;
        this.types = types;
    }

    @Override
    public Boolean visitArrayType(final ArrayType arrayType, final TypeMirror other) {
        return subTypeMatcher.visitArrayType(arrayType, other);
    }

    @Override
    public Boolean visitClassType(final ClassType classType, final TypeMirror other) {
        return subTypeMatcher.visitClassType(classType, other);
    }

    @Override
    public Boolean visitMethodType(final MethodType methodType, final TypeMirror other) {
        return subTypeMatcher.visitMethodType(methodType, other);
    }

    @Override
    public Boolean visitVoidType(final VoidType voidType, final TypeMirror other) {
        return subTypeMatcher.visitVoidType(voidType, other);
    }

    @Override
    public Boolean visitPrimitiveType(final PrimitiveType primitiveType, final TypeMirror other) {
        if (other instanceof PrimitiveType otherPrimitive) {
            return primitiveType.getKind() == otherPrimitive.getKind();
        } else if (other instanceof ClassType classType) {
            final var clazz = (ClassSymbol) classType.asElement();
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
                final var boxedElement = (ClassSymbol) types.boxedClass(primitiveType);
                final var boxedType = boxedElement.asType();
                isAssignable = boxedType.accept(subTypeMatcher, other);
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
    public Boolean visitVariableType(final VariableType variableType, final TypeMirror param) {
        return null;
    }

    @Override
    public Boolean visitWildcardType(final WildcardType wildcardType, final TypeMirror param) {
        return false;
    }

    @Override
    public Boolean visitTypeVariable(final TypeVariable typeVariable, final TypeMirror param) {
        return false;
    }
}

class SameTypeMatcher implements TypeVisitor<Boolean, TypeMirror> {

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
    public Boolean visitClassType(final ClassType classType, final TypeMirror otherType) {
        if (otherType instanceof ClassType otherClassType) {
            final var clazz = (ClassSymbol) classType.asElement();
            final var otherClass = (ClassSymbol) otherClassType.asElement();

            if (!clazz.getQualifiedName().equals(otherClass.getQualifiedName())) {
                return false;
            }

            if (getParameterTypeCount(classType) != getParameterTypeCount(otherClassType)) {
                return false;
            }

            final var parameterTypes = classType.getParameterTypes();

            if (parameterTypes == null) {
                return true;
            }

            final var otherParameterTypes = otherClassType.getParameterTypes();

            for (int i = 0; i < parameterTypes.size(); i++) {
                if (!parameterTypes.get(i).accept(this, otherParameterTypes.get(i))) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    /**
     @return Return the number of parameter types.
     Returns -1 in case of parameterTypes is null to distinct it
     from an empty parameter type list.
     */
    private int getParameterTypeCount(final ClassType classType) {
        return classType.getParameterTypes() != null
                ? classType.getParameterTypes().size()
                : -1;
    }

    @Override
    public Boolean visitMethodType(final MethodType methodType, final TypeMirror otherType) {
        if (otherType instanceof MethodType otherMethodType) {
            final var argumentTypes = methodType.getArgumentTypes();
            final var otherArgumentTypes = otherMethodType.getArgumentTypes();

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
    public Boolean visitVoidType(final VoidType voidType, final TypeMirror otherType) {
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