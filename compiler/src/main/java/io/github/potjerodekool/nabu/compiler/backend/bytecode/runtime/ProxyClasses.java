package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.reflect.InvocationHandler;

/**
 * Placeholder for the proxy classes of a native executable: the compiler
 * generates this class (with the same name) from the proxy configuration,
 * see {@code ProxyClassGenerator}. Only the signatures here matter.
 */
public final class ProxyClasses {

    private ProxyClasses() {
    }

    /** A proxy for the interfaces in {@code key} (binary names, comma-separated), or null if none is configured. */
    public static Object create(final String key, final InvocationHandler h) {
        return null;
    }

    /** Whether {@code className} is a generated proxy class. */
    public static boolean isProxyClass(final String className) {
        return false;
    }

    /**
     * Calls the interface (or Object) method {@code key} ("owner#name#descriptor")
     * on {@code target}, unboxing {@code args} and boxing the result; what
     * the method throws is wrapped in an InvocationTargetException.
     */
    public static Object invoke(final String key, final Object target, final Object[] args) throws Exception {
        throw new IllegalArgumentException(key);
    }
}
