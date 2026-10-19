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
