package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * Enum-valued elements of annotation types (see {@code AnnotationAccessors}
 * and {@code SourceAnnotationAccessors}): the reflection metadata holds the
 * constant's name; these return the constants themselves, so == holds.
 */
public final class AnnotationSupport {

    private AnnotationSupport() {
    }

    /** A annotation object of the type without any explicit values (every element has its default). */
    private static native Object blank0(Class<?> type);

    /**
     * {@code Method.getDefaultValue} of an element of an annotation type: the element's accessor on an annotation
     * without values answers with the default of the {@code AnnotationDefault} attribute. Null for a method that is
     * not an annotation element (an element without default gives the zero value of its type).
     */
    public static Object defaultValue(final java.lang.reflect.Method method) {
        final Class<?> owner = method.getDeclaringClass();
        if (!owner.isAnnotation() || method.getParameterCount() != 0) {
            return null;
        }
        try {
            // Not method.invoke: the redirect of Method.invoke does not apply to the classes of this package.
            return ProxySupport.invoke(method, blank0(owner), new Object[0]);
        } catch (final Exception e) {
            return null;
        }
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
