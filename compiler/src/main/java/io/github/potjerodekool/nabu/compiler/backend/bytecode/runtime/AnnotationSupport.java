package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * Enum-valued elements of annotation types (see {@code AnnotationAccessors}
 * and {@code SourceAnnotationAccessors}): the reflection metadata holds the
 * constant's name; these return the constants themselves, so == holds.
 */
public final class AnnotationSupport {

    private AnnotationSupport() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object enumValue(final String constant, final Class<?> type) {
        return constant == null ? null : Enum.valueOf((Class) type, constant);
    }

    /** An array of enum constants of {@code type} (an array of that type), by name. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object enumValues(final String[] constants, final Class<?> type) {
        final Object array = java.lang.reflect.Array.newInstance(type, constants.length);
        for (int i = 0; i < constants.length; i++) {
            java.lang.reflect.Array.set(array, i, Enum.valueOf((Class) type, constants[i]));
        }
        return array;
    }
}
