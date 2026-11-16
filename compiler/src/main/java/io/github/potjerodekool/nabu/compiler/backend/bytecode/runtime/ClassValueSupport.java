package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.util.IdentityHashMap;

/**
 * The storage of {@link ClassValue}: the JDK keeps the values in a field of {@code java.lang.Class}, which the
 * classes of a native executable do not have. {@code ClassValue.get} is replaced by a body that asks {@link #lookup},
 * computes the value itself (computeValue is protected) when there is none and {@link #store}s it.
 */
public final class ClassValueSupport {

    /** Returned by {@link #lookup} when the value was not computed yet (null is a value too). */
    public static final Object ABSENT = new Object();

    private static final IdentityHashMap<Object, IdentityHashMap<Class<?>, Object>> VALUES = new IdentityHashMap<>();

    private ClassValueSupport() {
    }

    public static Object lookup(final ClassValue<?> classValue, final Class<?> type) {
        synchronized (VALUES) {
            final IdentityHashMap<Class<?>, Object> values = VALUES.get(classValue);
            if (values == null || !values.containsKey(type)) {
                return ABSENT;
            }
            return values.get(type);
        }
    }

    /** Keeps the value unless another thread was first; returns the value that stays. */
    public static Object store(final ClassValue<?> classValue, final Class<?> type, final Object value) {
        synchronized (VALUES) {
            final IdentityHashMap<Class<?>, Object> values =
                    VALUES.computeIfAbsent(classValue, k -> new IdentityHashMap<>());
            if (values.containsKey(type)) {
                return values.get(type);
            }
            values.put(type, value);
            return value;
        }
    }

    public static void remove(final ClassValue<?> classValue, final Class<?> type) {
        synchronized (VALUES) {
            final IdentityHashMap<Class<?>, Object> values = VALUES.get(classValue);
            if (values != null) {
                values.remove(type);
            }
        }
    }
}
