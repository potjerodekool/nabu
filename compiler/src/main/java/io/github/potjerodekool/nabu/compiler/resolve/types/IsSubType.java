package io.github.potjerodekool.nabu.compiler.resolve.types;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.HashMap;

public class IsSubType implements TypeVisitor<Boolean, TypeMirror> {

    private final Types types;

    public IsSubType(final Types types) {
        this.types = types;
    }

    @Override
    public Boolean visitUnknownType(final TypeMirror typeMirror, final TypeMirror param) {
        return false;
    }

    @Override
    public Boolean visitArrayType(final ArrayType arrayType,
                                  final TypeMirror otherType) {
        if (otherType.getKind() != TypeKind.ARRAY) {
            final var componentType = arrayType.getComponentType();

            if (componentType.isDeclaredType() &&
                    otherType.isDeclaredType()) {
                final var qualifiedName = getQualifiedName((DeclaredType) otherType);
                return Constants.OBJECT.equals(qualifiedName)
                        || Constants.CLONEABLE.equals(qualifiedName)
                        || Constants.SERIALIZABLE.equals(qualifiedName);
            } else if (componentType.isPrimitiveType()) {
                final var qualifiedName = getQualifiedName((DeclaredType) otherType);
                return Constants.OBJECT.equals(qualifiedName)
                        || Constants.CLONEABLE.equals(qualifiedName)
                        || Constants.SERIALIZABLE.equals(qualifiedName);
            } else {
                return false;
            }
        } else {
            final var componentType = arrayType.getComponentType();
            final var otherArrayType = (ArrayType) otherType;
            final var otherComponentType = otherArrayType.getComponentType();

            if (componentType.isReferenceType()
                    && otherComponentType.isReferenceType()) {
                return componentType.accept(this, otherComponentType);
            } else {
                return false;
            }
        }
    }

    private String getQualifiedName(final DeclaredType declaredType) {
        final var typeElement = (TypeElement) declaredType.asElement();
        return typeElement.getQualifiedName();
    }

    @Override
    public Boolean visitDeclaredType(final DeclaredType declaredType,
                                     final TypeMirror otherType) {
        if (declaredType instanceof ErrorType
                || otherType instanceof ErrorType) {
            return false;
        }

        if (otherType instanceof DeclaredType otherDeclaredType) {
            final var clazz = (TypeElement) declaredType.asElement();
            final var otherClass = (TypeElement) otherDeclaredType.asElement();

            if (clazz.getQualifiedName().equals(otherClass.getQualifiedName())) {
                if (declaredType.getTypeArguments().isEmpty()) {
                    return true;
                } else {
                    final var typeArgs = declaredType.getTypeArguments();
                    final var otherTypeArgs = otherDeclaredType.getTypeArguments();
                    boolean match = true;

                    for (var i = 0; i < typeArgs.size() && match; i++) {
                        final var typeArg = typeArgs.get(i);
                        final var otherTypeArg = otherTypeArgs.get(i);
                        match = typeArg.accept(this, otherTypeArg);
                    }

                    return match;
                }
            }

            final var interfaceMatch = clazz.getInterfaces().stream()
                    .map(interfaceType -> (DeclaredType) interfaceType)
                    .anyMatch(interfaceType -> {
                        final var mapped = tm(declaredType, interfaceType);
                        return mapped.accept(this, otherType);
                    });

            if (interfaceMatch) {
                return true;
            }

            if (clazz.getSuperclass() != null) {
                return clazz.getSuperclass().accept(this, otherType);
            } else {
                return false;
            }
        } else if (otherType instanceof TypeVariable otherTypeVariable) {
            if (otherTypeVariable.getUpperBound() != null) {
                return declaredType.accept(this, otherTypeVariable.getUpperBound());
            }
        } else if (otherType instanceof WildcardType otherWildCardType) {
            return switch (otherWildCardType.getBoundKind()) {
                case UNBOUND -> true;
                case EXTENDS -> declaredType.accept(this, otherWildCardType.getBound());
                case SUPER -> throw new TodoException();
            };
        }

        return false;
    }

    private TypeMirror tm(final DeclaredType declaredType,
                          final DeclaredType interfaceType) {
        final var typeElement = (TypeElement) declaredType.asElement();
        final var typeParameters = typeElement.getTypeParameters();

        final var typeArguments = declaredType.getTypeArguments();

        if (typeParameters.size() != typeArguments.size() || typeParameters.isEmpty()) {
            return types.getDeclaredType(
                    (TypeElement) interfaceType.asElement()
            );
        }

        final var map = new HashMap<String, TypeMirror>();

        for (var i = 0; i < typeParameters.size(); i++) {
            final var typeParamName = typeParameters.get(i).getSimpleName();
            final var typeArg = typeArguments.get(i);
            map.put(typeParamName, typeArg);
        }

        final var interfaceTypeArguments = interfaceType.getTypeArguments();
        final var typeArgs = new TypeMirror[interfaceType.getTypeArguments().size()];

        for (var i = 0; i < interfaceTypeArguments.size(); i++) {
            final var typeVariable = (TypeVariable) interfaceTypeArguments.get(i);
            final var name = typeVariable.asElement().getSimpleName();
            final var type = map.get(name);
            typeArgs[i] = type;
        }

        return types.getDeclaredType(
                (TypeElement) interfaceType.asElement(),
                typeArgs
        );
    }

    @Override
    public Boolean visitPrimitiveType(final PrimitiveType primitiveType, final TypeMirror otherType) {
        if (primitiveType.getKind() == otherType.getKind()) {
            return true;
        }

        if (!otherType.isPrimitiveType()) {
            return false;
        }

        final var otherPrimitiveType = (PrimitiveType) otherType;

        return switch (primitiveType.getKind()) {
            case FLOAT -> otherPrimitiveType.getKind() == TypeKind.DOUBLE;
            case LONG -> otherPrimitiveType.getKind() == TypeKind.FLOAT;
            case INT ->  otherPrimitiveType.getKind() == TypeKind.LONG;
            case CHAR, SHORT -> otherPrimitiveType.getKind() == TypeKind.INT;
            case BYTE -> otherPrimitiveType.getKind() == TypeKind.SHORT;
            default -> false;
        };
    }

    @Override
    public Boolean visitVariableType(final VariableType variableType, final TypeMirror otherType) {
        final var interferedType = variableType.getInterferedType();
        return interferedType.accept(this, otherType);
    }

    @Override
    public Boolean visitWildcardType(final WildcardType wildcardType,
                                     final TypeMirror otherType) {
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
    public Boolean visitTypeVariable(final TypeVariable typeVariable, final TypeMirror otherType) {
        boolean isSubType = false;

        if (otherType instanceof TypeVariable otherTypeVariable) {
            if (typeVariable.getUpperBound() != null) {
                if (otherTypeVariable.getUpperBound() != null) {
                    isSubType = typeVariable.getUpperBound().accept(this, otherTypeVariable.getUpperBound());
                }
            } else if (typeVariable.getLowerBound() != null) {
                if (otherTypeVariable.getLowerBound() != null) {
                    isSubType = typeVariable.getLowerBound().accept(this, otherTypeVariable.getLowerBound());
                }
            }
        } else if (otherType instanceof DeclaredType declaredType) {
            if (typeVariable.getUpperBound() != null) {
                return typeVariable.getUpperBound().accept(this, declaredType);
            } else if (typeVariable.getLowerBound() != null) {
                return typeVariable.getLowerBound().accept(this, declaredType);
            }
        } else if (otherType instanceof WildcardType wildcardType) {
            //TODO add tests
            isSubType = switch (wildcardType.getBoundKind()) {
                case UNBOUND -> true;
                case EXTENDS -> {
                    if (typeVariable.getUpperBound() != null) {
                        yield typeVariable.getUpperBound().accept(this, wildcardType.getBound());
                    } else if (typeVariable.getLowerBound() != null) {
                        yield typeVariable.getLowerBound().accept(this, wildcardType.getBound());
                    } else {
                        yield false;
                    }
                }
                case SUPER -> throw new TodoException();
            };
        }
        return isSubType;
    }
}
