package io.github.potjerodekool.nabu.compiler.resolve;

public final class ClassUtils {

    private ClassUtils() {
    }

    public static String toInternalName(final String name) {
        if (name == null) {
            throw new NullPointerException();
        }

        return name.replace(".", "/");
    }

    public static String getClassDescriptor(final String className) {
        final var internalName = ClassUtils.toInternalName(className);
        return "L" + internalName + ";";
    }
}
