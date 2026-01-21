package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

public final class ClassUtils {

    private ClassUtils() {
    }

    public static String getInternalName(final String name) {
        return name.replace(".", "/");
    }

    public static String getClassName(final String innerName) {
        return innerName.replace('/', '.');
    }

    public static String getClassDescriptor(final String className) {
        final var internalName = ClassUtils.getInternalName(className);
        return "L" + internalName + ";";
    }

    public static String getDescriptor(final TypeMirror typeMirror) {
        return switch (typeMirror.getKind()) {
            case VOID -> "V";
            case BOOLEAN -> "Z";
            case CHAR -> "C";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case FLOAT -> "F";
            case LONG -> "J";
            case DOUBLE -> "D";
            case DECLARED -> {
                final var declaredType = (DeclaredType) typeMirror;
                yield getClassDescriptor(declaredType.asTypeElement().getQualifiedName());
            }
            default -> throw new IllegalArgumentException("");
        };
    }
}
