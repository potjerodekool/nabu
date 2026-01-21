package io.github.potjerodekool.nabu.compiler.backend.generate.signature;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.type.*;

import java.util.List;
import java.util.stream.Collectors;

public final class SignatureGenerator {

    private static final TypeParamSignatureVisitor TYPE_PARAM_SIGNATURE_VISITOR = new TypeParamSignatureVisitor();

    static final StandardSignatureVisitor STANDARD_VISITOR = new StandardSignatureVisitor();

    private SignatureGenerator() {
    }

    public static String getClassSignature(final TypeElement typeElement) {
        if (isGeneric(typeElement)) {
            final List<? extends TypeParameterElement> typeParameters = typeElement.getTypeParameters();
            return getClassSignature(
                    typeParameters,
                    typeElement.getSuperclass(),
                    typeElement.getInterfaces()
            );
        } else {
            return null;
        }
    }

    private static boolean isGeneric(final TypeElement typeElement) {
        if (!typeElement.getTypeParameters().isEmpty()) {
            return true;
        } else if (typeElement.getSuperclass() instanceof DeclaredType superclass
                && isGeneric(superclass.asTypeElement())) {
            return true;
        } else {
            return typeElement.getInterfaces().stream()
                    .filter(it -> it instanceof DeclaredType)
                    .map(it -> (DeclaredType) it)
                    .map(DeclaredType::asTypeElement)
                    .anyMatch(SignatureGenerator::isGeneric);
        }
    }


    public static String getClassSignature(final List<? extends TypeParameterElement> typeParameters,
                                           final TypeMirror superType,
                                           final List<? extends TypeMirror> interfaceTypes) {
        final var typeParamSignature = TYPE_PARAM_SIGNATURE_VISITOR.visitTypeParameters(typeParameters);

        final var superSignature = superType != null
                ? superType.accept(STANDARD_VISITOR, null)
                : "";
        final var interfacesSignature = interfaceTypes.stream()
                .map(it -> it.accept(STANDARD_VISITOR, null))
                .collect(Collectors.joining(""));

        return typeParamSignature + superSignature + interfacesSignature;
    }

    public static String getFieldSignature(final TypeMirror fieldType) {
        return fieldType.accept(STANDARD_VISITOR, null);
    }

    public static String getMethodSignature(final ExecutableType methodType) {
        return methodType.accept(TYPE_PARAM_SIGNATURE_VISITOR, null);
    }

    public static String visitTypeParameters(final List<? extends TypeParameterElement> typeParameters) {
        return TYPE_PARAM_SIGNATURE_VISITOR.visitTypeParameters(typeParameters);
    }

}

abstract class AbstractTypeParamSignatureVisitor
        implements ElementVisitor<String, Void>, TypeVisitor<String, Void> {

    @Override
    public String visitUnknownType(final TypeMirror typeMirror, final Void param) {
        return "";
    }

    @Override
    public String visitUnknown(final Element e, final Void unused) {
        return "";
    }

    @Override
    public String visitTypeParameter(final TypeParameterElement typeParameterElement, final Void unused) {
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
        return "[" + arrayType.getComponentType().accept(this, param);
    }

    @Override
    public String visitDeclaredType(final DeclaredType declaredType, final Void param) {
        if (!declaredType.getTypeArguments().isEmpty()) {
            final var typeArgs = declaredType.getTypeArguments().stream()
                    .map(ta -> ta.accept(this, param))
                    .collect(Collectors.joining("", "<", ">"));
            var descriptor = getDescriptor(declaredType, false);
            return descriptor + typeArgs + ";";
        } else {
            return getDescriptor(declaredType, true);
        }
    }

    private String getDescriptor(final DeclaredType declaredType,
                                 final boolean addSemicolon) {
        final String name;
        final var typeElement = declaredType.asTypeElement();

        if (typeElement.getNestingKind() == NestingKind.TOP_LEVEL) {
            name = "L" + typeElement.getQualifiedName().replace('.', '/');
        } else if (typeElement.getNestingKind() == NestingKind.MEMBER) {
            final var simpleName = typeElement.getSimpleName();
            final var enclosingName = getDescriptor((DeclaredType) typeElement.asType().getEnclosingType(), false);
            name = enclosingName + "$" + simpleName;
        } else {
            throw new TodoException();
        }

        return addSemicolon ? name + ";" : name;
    }


    @Override
    public String visitMethodType(final ExecutableType methodType, final Void param) {
        final var typeParameters = methodType.getTypeVariables().stream()
                .map(tv -> (TypeParameterElement) tv.asElement())
                .toList();
        final var typeVarSignature = visitTypeParameters(typeParameters);
        final var parameterSignature = methodType.getParameterTypes().stream()
                .map(it -> it.accept(SignatureGenerator.STANDARD_VISITOR, param))
                .collect(Collectors.joining("", "(", ")"));
        final var returnTypeSignature = methodType.getReturnType().accept(this, param);
        return typeVarSignature + parameterSignature + returnTypeSignature;
    }

    @Override
    public String visitNoType(final NoType noType, final Void param) {
        return noType.getKind() == TypeKind.VOID
                ? "V"
                : "";
    }

    @Override
    public String visitPrimitiveType(final PrimitiveType primitiveType, final Void param) {
        return switch (primitiveType.getKind()) {
            case BOOLEAN -> "Z";
            case CHAR -> "C";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case FLOAT -> "F";
            case LONG -> "J";
            case DOUBLE -> "D";
            default -> throw new IllegalArgumentException("Not a primitive kind " + primitiveType.getKind());
        };
    }

    @Override
    public String visitWildcardType(final WildcardType wildcardType, final Void param) {
        final var boundSignature = wildcardType.getBound() != null
                ? wildcardType.getBound().accept(this, param)
                : null;

        return switch (wildcardType.getBoundKind()) {
            case EXTENDS -> "+" + boundSignature;
            case SUPER -> "-" + boundSignature;
            case UNBOUND -> "*";
        };
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

    public String visitTypeParameters(final List<? extends TypeParameterElement> typeParameters) {
        if (typeParameters.isEmpty()) {
            return "";
        }

        return typeParameters.stream()
                .map(tp -> tp.accept(this, null))
                .collect(Collectors.joining("", "<", ">"));
    }
}

class TypeParamSignatureVisitor extends AbstractTypeParamSignatureVisitor {

    @Override
    public String visitDeclaredType(final DeclaredType declaredType, final Void param) {
        final String signature = super.visitDeclaredType(declaredType, param);
        if (declaredType.asElement().getKind() == ElementKind.INTERFACE) {
            return ":" + signature;
        } else {
            return signature;
        }
    }
}

class StandardSignatureVisitor extends AbstractTypeParamSignatureVisitor {
}