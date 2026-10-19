package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.ArrayType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;

/**
 * JVM names, descriptors and access flags of the compiler's elements, for
 * what the compiler generates from them at build time (proxy classes,
 * reflection data). Types are erased.
 */
final class ElementSignatures {

    private ElementSignatures() {
    }

    static String internalName(final TypeElement type) {
        return type.getFlatName().replace('.', '/');
    }

    static boolean isInterface(final TypeElement type) {
        return type.getKind() == ElementKind.INTERFACE || type.getKind() == ElementKind.ANNOTATION_TYPE;
    }

    /** {@code (params)return} of a method. */
    static String descriptor(final ExecutableElement method) {
        final var descriptor = new StringBuilder("(");
        for (final var parameter : method.getParameters()) {
            descriptor.append(descriptor(parameter.asType()));
        }
        return descriptor.append(')').append(descriptor(method.getReturnType())).toString();
    }

    /** The erased JVM descriptor of a type. */
    static String descriptor(final TypeMirror type) {
        if (type == null) {
            return "Ljava/lang/Object;";
        }
        return switch (type.getKind()) {
            case BYTE -> "B";
            case SHORT -> "S";
            case CHAR -> "C";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case BOOLEAN -> "Z";
            case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) type).getComponentType());
            case TYPEVAR -> type instanceof TypeVariable variable && variable.getUpperBound() != null
                    ? descriptor(variable.getUpperBound())
                    : "Ljava/lang/Object;";
            // Erasure of an intersection is its first bound (JLS 4.6).
            case INTERSECTION -> type instanceof io.github.potjerodekool.nabu.type.IntersectionType intersection
                    && !intersection.getBounds().isEmpty()
                    ? descriptor(intersection.getBounds().getFirst())
                    : "Ljava/lang/Object;";
            case DECLARED -> type.asTypeElement() != null
                    ? "L" + internalName(type.asTypeElement()) + ";"
                    : "Ljava/lang/Object;";
            default -> "Ljava/lang/Object;";
        };
    }

    /**
     * The generic signature of a method (the class-file Signature attribute,
     * JVMS 4.7.9.1), or null if it has no type parameters and no generic
     * parameter or return types, as javac does.
     */
    static String genericSignature(final ExecutableElement method) {
        final boolean constructor = method.getKind() == ElementKind.CONSTRUCTOR;
        boolean generic = !method.getTypeParameters().isEmpty()
                || (!constructor && isGeneric(method.getReturnType()));
        for (final var parameter : method.getParameters()) {
            generic |= isGeneric(parameter.asType());
        }
        if (!generic) {
            return null;
        }
        final var signature = new StringBuilder();
        if (!method.getTypeParameters().isEmpty()) {
            signature.append('<');
            for (final var typeParameter : method.getTypeParameters()) {
                signature.append(typeParameter.getSimpleName());
                final var bounds = typeParameter.getBounds();
                if (bounds.isEmpty()) {
                    signature.append(":Ljava/lang/Object;");
                }
                for (final TypeMirror bound : bounds) {
                    final boolean isInterface = bound.asTypeElement() != null && isInterface(bound.asTypeElement());
                    signature.append(isInterface ? "::" : ":").append(signature(bound));
                }
            }
            signature.append('>');
        }
        signature.append('(');
        for (final var parameter : method.getParameters()) {
            signature.append(signature(parameter.asType()));
        }
        return signature.append(')').append(constructor ? "V" : signature(method.getReturnType())).toString();
    }

    private static boolean isGeneric(final TypeMirror type) {
        if (type == null) {
            return false;
        }
        return switch (type.getKind()) {
            case TYPEVAR, WILDCARD, INTERSECTION -> true;
            case ARRAY -> isGeneric(((ArrayType) type).getComponentType());
            case DECLARED -> type instanceof io.github.potjerodekool.nabu.type.DeclaredType declared
                    && declared.getTypeArguments() != null && !declared.getTypeArguments().isEmpty();
            default -> false;
        };
    }

    /** The generic signature of a type: type arguments, type variables and wildcards kept. */
    static String signature(final TypeMirror type) {
        if (type == null) {
            return "Ljava/lang/Object;";
        }
        return switch (type.getKind()) {
            case TYPEVAR -> type instanceof TypeVariable variable && variable.asElement() != null
                    ? "T" + variable.asElement().getSimpleName() + ";"
                    : "Ljava/lang/Object;";
            case ARRAY -> "[" + signature(((ArrayType) type).getComponentType());
            case WILDCARD -> {
                final var wildcard = (io.github.potjerodekool.nabu.type.WildcardType) type;
                yield switch (wildcard.getBoundKind()) {
                    case EXTENDS -> "+" + signature(wildcard.getBound());
                    case SUPER -> "-" + signature(wildcard.getBound());
                    default -> "*";
                };
            }
            case DECLARED -> {
                if (type.asTypeElement() == null) {
                    yield "Ljava/lang/Object;";
                }
                final var result = new StringBuilder("L").append(internalName(type.asTypeElement()));
                if (type instanceof io.github.potjerodekool.nabu.type.DeclaredType declared
                        && declared.getTypeArguments() != null && !declared.getTypeArguments().isEmpty()) {
                    result.append('<');
                    for (final TypeMirror argument : declared.getTypeArguments()) {
                        result.append(signature(argument));
                    }
                    result.append('>');
                }
                yield result.append(';').toString();
            }
            default -> descriptor(type);
        };
    }

    /** The declared exceptions of a method, as internal names. */
    static java.util.List<String> exceptions(final ExecutableElement method) {
        final java.util.List<String> exceptions = new java.util.ArrayList<>();
        for (final var thrown : method.getThrownTypes()) {
            final String desc = descriptor(thrown);
            if (desc.startsWith("L")) {
                exceptions.add(desc.substring(1, desc.length() - 1));
            }
        }
        return exceptions;
    }

    /** The descriptor of a type named as in a configuration ({@code int}, {@code a.B}, {@code a.B[]}). */
    static String descriptorOfName(final String name) {
        if (name.endsWith("[]")) {
            return "[" + descriptorOfName(name.substring(0, name.length() - 2));
        }
        return switch (name) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            case "void" -> "V";
            default -> name.startsWith("[") ? name.replace('.', '/') : "L" + name.replace('.', '/') + ";";
        };
    }

    /**
     * The access flags of a method as {@code Method.getModifiers} gives them:
     * an interface method is public, and abstract unless it is default,
     * static or private.
     */
    static int modifiers(final ExecutableElement method, final TypeElement owner) {
        int access = 0;
        if (method.isPublic()) access |= 0x0001;
        if (method.isPrivate()) access |= 0x0002;
        if (method.isProtected()) access |= 0x0004;
        if (method.isStatic()) access |= 0x0008;
        if (method.isFinal()) access |= 0x0010;
        if (method.isNative()) access |= 0x0100;
        if (method.isAbstract()) access |= 0x0400;
        if (method.isVarArgs()) access |= 0x0080;
        if (method.isSynthetic()) access |= 0x1000;
        if (isInterface(owner) && (access & 0x0002) == 0) {
            access |= 0x0001;
            if (!method.isDefault() && (access & 0x0008) == 0) {
                access |= 0x0400;
            }
        }
        return access;
    }
}
