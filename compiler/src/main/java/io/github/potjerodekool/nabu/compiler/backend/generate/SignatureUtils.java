package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.*;
import io.github.potjerodekool.nabu.compiler.resolve.ClassUtils;
import io.github.potjerodekool.nabu.compiler.type.*;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.stream.Collectors;

public final class SignatureUtils {

    private SignatureUtils() {
    }

    public static String getDescriptor(final IType type) {
        return toAsmType(type).getDescriptor();
    }

    public static String getMethodDescriptor(final List<? extends IType> parameterTypes,
                                             final IType returnType) {
        final var pTypes = parameterTypes.stream()
                .map(SignatureUtils::toAsmType)
                .toArray(Type[]::new);

        final var retType = toAsmType(returnType);
        return Type.getMethodDescriptor(retType, pTypes);
    }

    public static String getMethodSignature(final List<? extends IType> parameterTypes,
                                            final IType returnType) {
        if (parameterTypes.stream()
                .noneMatch(SignatureUtils::isGenericType)
                && !isGenericType(returnType)) {
            return null;
        }

        final var params = parameterTypes.stream()
                .map(pt -> pt.accept(SignatureGeneratorVisitor.INSTANCE, null))
                .collect(Collectors.joining("", "(", ")"));

        final var retType = returnType.accept(SignatureGeneratorVisitor.INSTANCE, null);
        return params + retType;
    }

    public static String getClassSignature(final List<? extends TypeParameterElement> typeParameters,
                                           final TypeMirror supertype) {
        final var typeParamVisitor = new TypeParamSignatureVisitor();

        final var typeParameterSignature = typeParameters.stream()
                .map(tp -> tp.accept(typeParamVisitor, null))
                .collect(Collectors.joining("", "<", ">"));

        final var superSignature = supertype.accept(typeParamVisitor, null);

        return typeParameterSignature + superSignature;
    }

    public static String getSignature(final IType type) {
        return switch (type) {
            case IReferenceType referenceType -> getSignature(referenceType);
            case IPrimitiveType primitiveType -> getSignature(primitiveType);
            case IWildcardType wildcardType -> getSignature(wildcardType);
            case ITypeVariable typeVariable -> getSignature(typeVariable);
            case IIntersectionType intersectionType -> getSignature(intersectionType);
        };
    }

    private static String getSignature(final IReferenceType referenceType) {
        return getSignature(referenceType, "");
    }

    private static String getSignature(final IIntersectionType intersectionType) {
        return intersectionType.getBounds().stream()
                .map(referenceType -> (IReferenceType) referenceType)
                .map(referenceType -> {
                    final var prefix = referenceType.getKind() == ITypeKind.INTERFACE
                            ? ":"
                            : "";
                    return SignatureUtils.getSignature(referenceType, prefix);
                })
                .collect(Collectors.joining(""));
    }

    private static String getSignature(final IReferenceType referenceType,
                                       final String prefix) {
        final var asmType = toAsmType(referenceType);
        if (!referenceType.getTypeArguments().isEmpty()) {
            final var typeArgs = referenceType.getTypeArguments().stream()
                    .map(SignatureUtils::getSignature)
                    .collect(Collectors.joining("", "<", ">"));
            var name = asmType.getDescriptor();
            name = name.substring(0, name.length() - 1);
            return prefix + name + typeArgs + ";";
        } else {
            return prefix + asmType.getDescriptor();
        }
    }

    private static String getSignature(final IPrimitiveType primitiveType) {
        return toAsmType(primitiveType).getDescriptor();
    }

    private static String getSignature(final IWildcardType wildcardType) {
        return "*";
    }

    private static String getSignature(final ITypeVariable typeVariable) {
        final var name = typeVariable.getName();

        if (typeVariable.getUpperBound() != null) {
            return "T" + name + ":" + getSignature(typeVariable.getUpperBound());
        } else if (typeVariable.getLowerBound() != null) {
            return "T" + name + "-" + getSignature(typeVariable.getLowerBound());
        } else {
            return "T" + name + ";";
        }
    }

    private static boolean isGenericType(final IType type) {
        if (type instanceof IReferenceType referenceType) {
            return referenceType.getTypeArguments() != null;
        } else {
            return false;
        }
    }

    static Type toAsmType(final IType type) {
        if (type == null) {
            return Type.VOID_TYPE;
        } else {
            return switch (type) {
                case IReferenceType referenceType -> {
                    final var name = referenceType.getName();
                    var descriptor = ClassUtils.getClassDescriptor(name);
                    yield Type.getType(descriptor);
                }
                case IWildcardType ignored -> throw new TodoException();
                case IPrimitiveType primitiveType -> switch (primitiveType.getKind()) {
                    case CHAR -> Type.CHAR_TYPE;
                    case BYTE -> Type.BYTE_TYPE;
                    case BOOLEAN -> Type.BOOLEAN_TYPE;
                    case INT -> Type.INT_TYPE;
                    case DOUBLE -> Type.DOUBLE_TYPE;
                    case FLOAT -> Type.FLOAT_TYPE;
                    case LONG -> Type.LONG_TYPE;
                    case SHORT -> Type.SHORT_TYPE;
                    case VOID -> Type.VOID_TYPE;
                    default ->
                            throw new IllegalStateException("Invalid primitive type " + primitiveType.getKind().name());
                };
                case ITypeVariable typeVariable -> typeVariable.getUpperBound() != null
                        ? toAsmType(typeVariable.getUpperBound())
                        : toAsmType(typeVariable.getLowerBound());
                case IIntersectionType ignored -> throw new TodoException();
            };
        }
    }
}

abstract class AbstractSignatureGeneratorVisitor implements ITypeVisitor<String, Void> {

    @Override
    public String visitIntersectionType(final IIntersectionType intersectionType, final Void param) {
        return intersectionType.getBounds().stream()
                .map(referenceType -> (IReferenceType) referenceType)
                .map(referenceType -> referenceType.accept(this, param))
                .collect(Collectors.joining(""));
    }

    @Override
    public String visitPrimitiveType(final IPrimitiveType primitiveType, final Void param) {
        return SignatureUtils.toAsmType(primitiveType).getDescriptor();
    }

    @Override
    public String visitReferenceType(final IReferenceType referenceType, final Void param) {
        final var asmType = SignatureUtils.toAsmType(referenceType);
        if (!referenceType.getTypeArguments().isEmpty()) {
            final var typeArgs = referenceType.getTypeArguments().stream()
                    .map(ta -> ta.accept(this, param))
                    .collect(Collectors.joining("", "<", ">"));
            var name = asmType.getDescriptor();
            name = name.substring(0, name.length() - 1);
            return name + typeArgs + ";";
        } else {
            return asmType.getDescriptor();
        }
    }


    @Override
    public String visitWildcardType(final IWildcardType wildcardType, final Void param) {
        return "*";
    }
}

class SignatureGeneratorVisitor extends AbstractSignatureGeneratorVisitor {

    static final SignatureGeneratorVisitor INSTANCE = new SignatureGeneratorVisitor();

    private SignatureGeneratorVisitor() {
    }

    @Override
    public String visitTypeVariableType(final ITypeVariable typeVariable, final Void param) {
        final var name = typeVariable.getName();

        if (typeVariable.getUpperBound() != null) {
            return "T" + name + ":" + typeVariable.getUpperBound().accept(this, param);
        } else if (typeVariable.getLowerBound() != null) {
            return "T" + name + ":" + typeVariable.getLowerBound().accept(this, param);
        } else {
            return "T" + name + ";";
        }
    }


}

abstract class AbstractSignatureGeneratorVisitor2
        implements ElementVisitor<String, Void>, TypeVisitor<String, Void> {

    @Override
    public String visitUnknownType(final TypeMirror typeMirror, final Void param) {
        return "";
    }

    @Override
    public String visitExecutableElement(final ExecutableElement methodSymbol, final Void unused) {
        return "";
    }

    @Override
    public String visitTypeParameterElement(final TypeParameterElement typeParameterElement, final Void unused) {
        final var name = typeParameterElement.getSimpleName();
        final var typeVariable = (TypeVariable) typeParameterElement.asType();
        final String typeSignature;

        if (typeVariable.getUpperBound() != null) {
            typeSignature = typeVariable.getUpperBound().accept(this, unused);
        } else if (typeVariable.getLowerBound() != null) {
            typeSignature = typeVariable.getLowerBound().accept(this, unused);
        } else {
            typeSignature = "";
        }

        return name + ":" + typeSignature;
    }

    @Override
    public String visitArrayType(final ArrayType arrayType, final Void param) {
        throw new TodoException();
    }

    @Override
    public String visitDeclaredType(final DeclaredType classType, final Void param) {
        final var asmType = toAsmType(classType);
        if (!classType.getTypeArguments().isEmpty()) {
            final var typeArgs = classType.getTypeArguments().stream()
                    .map(ta -> ta.accept(this, param))
                    .collect(Collectors.joining("", "<", ">"));
            var name = asmType.getDescriptor();
            name = name.substring(0, name.length() - 1);
            return name + typeArgs + ";";
        } else {
            return asmType.getDescriptor();
        }
    }

    @Override
    public String visitMethodType(final ExecutableType methodType, final Void param) {
        throw new TodoException();
    }

    @Override
    public String visitNoType(final NoType noType, final Void param) {
        throw new TodoException();
    }

    @Override
    public String visitPrimitiveType(final PrimitiveType primitiveType, final Void param) {
        throw new TodoException();
    }

    @Override
    public String visitNullType(final NullType nullType, final Void param) {
        throw new TodoException();
    }

    @Override
    public String visitVariableType(final VariableType variableType, final Void param) {
        throw new TodoException();
    }

    @Override
    public String visitWildcardType(final WildcardType wildcardType, final Void param) {
        throw new TodoException();
    }

    @Override
    public String visitTypeVariable(final TypeVariable typeVariable, final Void param) {
        final var name = typeVariable.asElement().getSimpleName();
        return "T" + name + ";";
    }

    @Override
    public String visitIntersectionType(final IntersectionType intersectionType, final Void param) {
        return intersectionType.getBounds().stream()
                .map(declaredType -> (DeclaredType) declaredType)
                .map(declaredType -> declaredType.accept(this, param))
                .collect(Collectors.joining());
    }

    @Override
    public String visitNoneType(final NoType noType, final Void param) {
        throw new TodoException();
    }

    public static Type toAsmType(final TypeMirror type) {
        if (type == null) {
            return Type.VOID_TYPE;
        } else {
            return switch (type) {
                case DeclaredType declaredType -> {
                    final var typeElement = (TypeElement) declaredType.asElement();
                    final var name = typeElement.getQualifiedName();
                    var descriptor = ClassUtils.getClassDescriptor(name);
                    yield Type.getType(descriptor);
                }
                case WildcardType ignored -> throw new TodoException();
                case PrimitiveType primitiveType -> switch (primitiveType.getKind()) {
                    case CHAR -> Type.CHAR_TYPE;
                    case BYTE -> Type.BYTE_TYPE;
                    case BOOLEAN -> Type.BOOLEAN_TYPE;
                    case INT -> Type.INT_TYPE;
                    case DOUBLE -> Type.DOUBLE_TYPE;
                    case FLOAT -> Type.FLOAT_TYPE;
                    case LONG -> Type.LONG_TYPE;
                    case SHORT -> Type.SHORT_TYPE;
                    case VOID -> Type.VOID_TYPE;
                    default ->
                            throw new IllegalStateException("Invalid primitive type " + primitiveType.getKind().name());
                };
                case TypeVariable ignored -> throw new TodoException();
                case IntersectionType ignored -> throw new TodoException();
                default -> throw new IllegalStateException("Unexpected value: " + type);
            };
        }
    }
}

class TypeParamSignatureVisitor extends AbstractSignatureGeneratorVisitor2 {

    @Override
    public String visitDeclaredType(final DeclaredType classType, final Void param) {
        final String signature = super.visitDeclaredType(classType, param);
        if (classType.asElement().getKind() == ElementKind.INTERFACE) {
            return ":" + signature;
        } else {
            return signature;
        }
    }

}