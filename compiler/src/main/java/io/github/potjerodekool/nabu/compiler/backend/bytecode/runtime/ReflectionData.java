package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.reflect.Method;

/**
 * Placeholder for the method reflection data of a native executable: the
 * compiler generates this class (with the same name) from the reflection
 * and proxy configuration, see {@code ReflectionDataGenerator}. Only the
 * signatures here matter.
 */
public final class ReflectionData {

    private ReflectionData() {
    }

    /** The registered methods of {@code className}: public incl. inherited, or declared; null if none. */
    public static Method[] methods(final String className, final boolean publicOnly) {
        return null;
    }

    /** The registered constructors of {@code className}, null if it has no data. */
    public static java.lang.reflect.Constructor<?>[] constructors(final String className) {
        return null;
    }

    /**
     * Calls the method {@code key} ("owner#name#descriptor") on
     * {@code target} (ignored for a static method), unboxing {@code args}
     * and boxing the result; what the method throws is wrapped in an
     * InvocationTargetException.
     */
    public static Object invoke(final String key, final Object target, final Object[] args) throws Exception {
        throw new IllegalArgumentException(key);
    }
}
