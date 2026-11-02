package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

public final class ClassUtils {

    private ClassUtils() {
    }

    /**
     * Index of the last <code>$</code> that separates an outer class from a member class, or -1. A <code>$</code>
     * next to another <code>$</code> (<code>Config$$SpringCGLIB$$0</code>) or at the start or end of the name is
     * part of the name: it is a valid identifier character, and generators use it for top-level classes.
     */
    public static int lastNestingSeparator(final String name) {
        for (var i = name.length() - 2; i > 0; i--) {
            if (isNestingSeparator(name, i)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean hasNestingSeparator(final String name) {
        return lastNestingSeparator(name) > -1;
    }

    /** Splits a binary class name at its nesting separators (see {@link #lastNestingSeparator}). */
    public static java.util.List<String> splitNested(final String name) {
        final var parts = new java.util.ArrayList<String>();
        var start = 0;
        for (var i = 1; i < name.length() - 1; i++) {
            if (isNestingSeparator(name, i)) {
                parts.add(name.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(name.substring(start));
        return parts;
    }

    private static boolean isNestingSeparator(final String name, final int index) {
        return name.charAt(index) == '$'
                && name.charAt(index - 1) != '$'
                && name.charAt(index + 1) != '$';
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
