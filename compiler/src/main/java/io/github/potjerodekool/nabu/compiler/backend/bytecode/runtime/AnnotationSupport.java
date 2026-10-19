package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * Enum-valued elements of annotation types compiled from source (see
 * {@code SourceAnnotationAccessors}): the reflection metadata holds the
 * constant's name; this returns the constant itself, so == holds.
 */
public final class AnnotationSupport {

    private AnnotationSupport() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object enumValue(final String constant, final Class<?> type) {
        return constant == null ? null : Enum.valueOf((Class) type, constant);
    }
}
